package com.example.fluxnova;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

class TimeZoneTest {

    private final TimeZone original = TimeZone.getDefault();

    @AfterEach
    void restore() {
        TimeZone.setDefault(original);
    }

    @Test
    void legacyAliasOfTheHostMachineIsReplacedByUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Calcutta"));   // what an Indian Windows machine reports

        FluxnovaMultiTenantApplication.useUtc();

        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
    }
}
