package com.condense.ir;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.corpus.CorpusCatalog;
import com.condense.corpus.CorpusRunner;
import com.condense.filter.cloud.DockerPsFilter;
import com.condense.filter.node.ESLintFilter;
import com.condense.filter.node.NpmInstallFilter;
import com.condense.trust.Provenance;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IrRendererGoldenTest {

    private static final Set<String> EXEMPLARS = Set.of(
        "pytest/typical",
        "eslint/typical",
        "eslint/passing",
        "eslint-json/typical",
        "npm-install/typical",
        "npm-install/with-vulns",
        "docker-ps/typical"
    );

    @Test
    void textRendererPlusStampMatchesApplyOutput() throws Exception {
        for (CorpusCatalog.Entry entry : CorpusCatalog.load().entries()) {
            if (!EXEMPLARS.contains(entry.id())) {
                continue;
            }
            FilterResult applied = CorpusRunner.apply(entry);
            assertThat(applied.document())
                .as(entry.id() + " must attach a document")
                .isNotNull();
            String rendered = Provenance.stamp(TextRenderer.render(applied.document()));
            assertThat(rendered)
                .as(entry.id() + " TextRenderer must stay golden-identical")
                .isEqualTo(applied.output());
            for (String signal : entry.criticalSignals()) {
                assertThat(applied.output())
                    .as(entry.id() + " retains " + signal)
                    .contains(signal);
            }
        }
    }

    @Test
    void pytestFailureStaysTyped() throws Exception {
        FilterResult applied = CorpusRunner.apply(entry("pytest/typical"));
        assertThat(applied.document().kind()).isEqualTo(Document.DocumentKind.TEST);
        assertThat(applied.document().wasFiltered()).isTrue();
        assertThat(applied.document().childExitCode()).isEqualTo(1);
    }

    @Test
    void npmFailureWithWarnStaysTyped() {
        FilterResult applied = new NpmInstallFilter().apply(
            "npm install",
            new ExecutionResult(1, "npm warn deprecated foo@1.0.0: gone\n", "", 4L),
            CondenseConfig.defaults(),
            0,
            false);
        assertThat(applied.document().kind()).isEqualTo(Document.DocumentKind.DEPENDENCY);
        assertThat(applied.output()).contains("npm install failed");
        Document.DependencyDocument payload = (Document.DependencyDocument) applied.document().document();
        assertThat(payload.failed()).isTrue();
        assertThat(payload.irrevocable()).isNotEmpty();
    }

    @Test
    void dockerPsFailurePassthroughIsOpaque() {
        FilterResult applied = new DockerPsFilter().apply(
            "docker ps",
            new ExecutionResult(1, "Cannot connect to the Docker daemon\n", "", 2L),
            CondenseConfig.defaults(),
            0,
            false);
        assertThat(applied.wasFiltered()).isFalse();
        assertThat(applied.document().kind()).isEqualTo(Document.DocumentKind.OPAQUE);
        assertThat(applied.document().wasFiltered()).isFalse();
        assertThat(applied.document().provenance().applied()).isFalse();
    }

    @Test
    void eslintWithoutParseableIssuesStillHasADocument() {
        FilterResult applied = new ESLintFilter().apply(
            "eslint",
            new ExecutionResult(1, "not-json-and-no-lint-lines\n", "", 2L),
            CondenseConfig.defaults(),
            0,
            false);
        assertThat(applied.document()).isNotNull();
        assertThat(applied.document().kind()).isEqualTo(Document.DocumentKind.DIAGNOSTIC);
    }

    private static CorpusCatalog.Entry entry(String id) throws Exception {
        return CorpusCatalog.load().entries().stream()
            .filter(e -> id.equals(e.id()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("missing corpus row " + id));
    }
}
