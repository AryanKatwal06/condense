package com.condense.filter.cloud;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DockerPsFilterTest extends FilterTestSupport {

    private DockerPsFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new DockerPsFilter(); config = CondenseConfig.defaults(); }

    @Test
    void compactsTableToEssentialColumns() throws Exception {
        FilterResult r = filter.apply("docker ps",
            success(fixture("docker-ps", "typical")), config, 0, false);
        assertThat(r.output()).contains("web-server");
        assertThat(r.output()).contains("database");
        assertCompressed(r);
    }

    @Test
    void emptyDockerPs_returnsNoContainersMessage() {
        FilterResult r = filter.apply("docker ps",
            success("CONTAINER ID   IMAGE   COMMAND   CREATED   STATUS   PORTS   NAMES"),
            config, 0, false);
        assertThat(r.output()).contains("no containers");
    }
}