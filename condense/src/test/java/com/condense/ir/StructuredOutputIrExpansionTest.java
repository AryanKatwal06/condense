package com.condense.ir;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterIncident;
import com.condense.filter.stage.GradleSummaryStage;
import com.condense.filter.stage.MakeSummaryStage;
import com.condense.filter.stage.MvnSummaryStage;
import com.condense.filter.strategy.GitStatusStage;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredOutputIrExpansionTest {

    @Test
    void gitDocumentRoundTripsAndStrictlyValidates() {
        Document.GitDocument gitDoc = new Document.GitDocument(
            "main",
            false,
            List.of("src/Main.java"),
            List.of("README.md"),
            List.of("untracked.txt"),
            1,
            2,
            "[main] staged: 1 | modified: 1 | untracked: 1",
            "status",
            List.of("S src/Main.java", "M README.md", "? untracked.txt")
        );
        Document doc = Document.of(
            Document.DocumentKind.GIT,
            "git status",
            "GitStatusFilter",
            0,
            true,
            Documents.provenance(true),
            gitDoc
        );

        String json = JsonRenderer.render(doc);
        Document parsed = JsonRenderer.parse(json);
        assertThat(parsed.kind()).isEqualTo(Document.DocumentKind.GIT);
        assertThat(parsed.schemaVersion()).isEqualTo(Document.SCHEMA_VERSION);

        Document.GitDocument parsedGit = (Document.GitDocument) parsed.document();
        assertThat(parsedGit.branch()).isEqualTo("main");
        assertThat(parsedGit.clean()).isFalse();
        assertThat(parsedGit.staged()).containsExactly("src/Main.java");
        assertThat(parsedGit.modified()).containsExactly("README.md");
        assertThat(parsedGit.untracked()).containsExactly("untracked.txt");
        assertThat(parsedGit.ahead()).isEqualTo(1);
        assertThat(parsedGit.behind()).isEqualTo(2);
        assertThat(parsedGit.subcommand()).isEqualTo("status");

        String text = TextRenderer.render(parsed);
        assertThat(text).contains("[main] staged: 1 | modified: 1 | untracked: 1");

        String withUnknown = json.substring(0, json.lastIndexOf('}')) + ",\"extra\":\"bad\"}}";
        assertThatThrownBy(() -> JsonRenderer.parse(withUnknown))
            .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void buildDocumentRoundTripsAndStrictlyValidates() {
        Document.BuildDocument buildDoc = new Document.BuildDocument(
            "mvn",
            "FAILURE",
            2,
            0,
            1200L,
            List.of("compile"),
            List.of("✗ BUILD FAILURE", "[ERROR] Compilation failure in Foo.java")
        );
        Document doc = Document.of(
            Document.DocumentKind.BUILD,
            "mvn compile",
            "MvnFilter",
            1,
            true,
            Documents.provenance(true),
            buildDoc
        );

        String json = JsonRenderer.render(doc);
        Document parsed = JsonRenderer.parse(json);
        assertThat(parsed.kind()).isEqualTo(Document.DocumentKind.BUILD);

        Document.BuildDocument parsedBuild = (Document.BuildDocument) parsed.document();
        assertThat(parsedBuild.tool()).isEqualTo("mvn");
        assertThat(parsedBuild.status()).isEqualTo("FAILURE");
        assertThat(parsedBuild.errors()).isEqualTo(2);
        assertThat(parsedBuild.durationMs()).isEqualTo(1200L);
        assertThat(parsedBuild.failedTasks()).containsExactly("compile");

        String text = TextRenderer.render(parsed);
        assertThat(text).contains("✗ BUILD FAILURE");

        String withUnknown = json.substring(0, json.lastIndexOf('}')) + ",\"extra\":\"bad\"}}";
        assertThatThrownBy(() -> JsonRenderer.parse(withUnknown))
            .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void gitStatusStagePopulatesGitDocument() {
        FilterContext context = FilterContext.of(
            "git status",
            new ExecutionResult(0, "", "", 10L),
            CondenseConfig.defaults(),
            0,
            false
        );
        String input = "On branch feature/phase8\nChanges not staged for commit:\n\tmodified:   Foo.java\n";
        GitStatusStage.INSTANCE.process(input, context);

        Document document = Documents.fromContext(
            context, "git status", "git-status", context.result(), true, "fallback"
        );
        assertThat(document.kind()).isEqualTo(Document.DocumentKind.GIT);
        Document.GitDocument git = (Document.GitDocument) document.document();
        assertThat(git.branch()).isEqualTo("feature/phase8");
        assertThat(git.clean()).isFalse();
        assertThat(git.modified()).containsExactly("Foo.java");
        assertThat(git.summary()).contains("modified: 1");
    }

    @Test
    void buildStagesPopulateBuildDocument() {
        // Mvn
        FilterContext mvnCtx = FilterContext.of(
            "mvn test",
            new ExecutionResult(0, "", "", 10L),
            CondenseConfig.defaults(),
            0,
            false
        );
        MvnSummaryStage.INSTANCE.process("BUILD SUCCESS\nTests run: 5, Failures: 0, Errors: 0, Time elapsed: 1.2 s", mvnCtx);
        Document mvnDoc = Documents.fromContext(
            mvnCtx, "mvn test", "mvn", mvnCtx.result(), true, "fallback"
        );
        assertThat(mvnDoc.kind()).isEqualTo(Document.DocumentKind.BUILD);
        Document.BuildDocument mvnBuild = (Document.BuildDocument) mvnDoc.document();
        assertThat(mvnBuild.tool()).isEqualTo("mvn");
        assertThat(mvnBuild.status()).isEqualTo("SUCCESS");

        // Gradle
        FilterContext gradleCtx = FilterContext.of(
            "gradle build",
            new ExecutionResult(1, "", "", 10L),
            CondenseConfig.defaults(),
            0,
            false
        );
        GradleSummaryStage.INSTANCE.process("BUILD FAILED\n> Task :compileJava FAILED", gradleCtx);
        Document gradleDoc = Documents.fromContext(
            gradleCtx, "gradle build", "gradle", gradleCtx.result(), true, "fallback"
        );
        assertThat(gradleDoc.kind()).isEqualTo(Document.DocumentKind.BUILD);
        Document.BuildDocument gradleBuild = (Document.BuildDocument) gradleDoc.document();
        assertThat(gradleBuild.tool()).isEqualTo("gradle");
        assertThat(gradleBuild.status()).isEqualTo("FAILED");

        // Make
        FilterContext makeCtx = FilterContext.of(
            "make all",
            new ExecutionResult(0, "", "", 10L),
            CondenseConfig.defaults(),
            0,
            false
        );
        MakeSummaryStage.INSTANCE.process("Building targets...\nmake: done", makeCtx);
        Document makeDoc = Documents.fromContext(
            makeCtx, "make all", "make", makeCtx.result(), true, "fallback"
        );
        assertThat(makeDoc.kind()).isEqualTo(Document.DocumentKind.BUILD);
        Document.BuildDocument makeBuild = (Document.BuildDocument) makeDoc.document();
        assertThat(makeBuild.tool()).isEqualTo("make");
        assertThat(makeBuild.status()).isEqualTo("SUCCESS");
    }

    @Test
    void gitAndBuildFailOpenPreservingExitCode() {
        FilterContext context = FilterContext.of(
            "git status",
            new ExecutionResult(1, "raw", "", 1L),
            CondenseConfig.defaults(),
            0,
            false
        );
        context.documentBuilder().git(new Document.GitDocument("main", true, List.of(), List.of(), List.of()));
        context.documentBuilder().failNextBuild(new IllegalStateException("simulated git ir failure"));

        Document doc = Documents.fromContext(
            context, "git status", "git-status", context.result(), true, "fallback body"
        );
        assertThat(doc.kind()).isEqualTo(Document.DocumentKind.OPAQUE);
        assertThat(doc.childExitCode()).isEqualTo(1);
        assertThat(((Document.OpaqueDocument) doc.document()).body()).isEqualTo("fallback body");
        assertThat(context.incidents())
            .extracting(FilterIncident::kind)
            .contains(FilterIncident.KIND_IR_FALLBACK);
    }
}
