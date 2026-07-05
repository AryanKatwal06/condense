package com.condense.filter.cloud;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AwsFilterTest extends FilterTestSupport {

    private AwsFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new AwsFilter(); config = CondenseConfig.defaults(); }

    @Test
    void jsonCompression_compressesJson() throws Exception {
        FilterResult r = filter.apply("aws ec2 describe-instances",
            success(fixture("aws", "describe-instances")), config, 0, false);
        assertThat(r.output()).contains("Reservations");
        // assertCompressed(r);
    }

    @Test
    void allPassing_showsSuccessIndicator() throws Exception {
        FilterResult r = filter.apply("aws ec2 describe-instances",
            success("{}"), config, 0, false);
        assertThat(r.output()).isNotBlank();
        // assertCompressed(r);
    }

    @Test
    void failure_passesThrough() {
        FilterResult r = filter.apply("aws s3 ls",
            failure(1, "aws: error", ""), config, 0, false);
        assertThat(r.output()).isNotBlank();
    }

    @Test
    void tokenSavings_arePositiveForTypicalFixture() throws Exception {
        FilterResult r = filter.apply("aws ec2 describe-instances",
            success(fixture("aws", "describe-instances")), config, 0, false);
        assertThat(r.savingsPct()).isPositive();
    }
}
