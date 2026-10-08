package org.miezmerker.backend;

import org.junit.jupiter.api.Tag;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("system")
class SystemControllerTest {
    @Test void versionUsesBuildMetadataRatherThanAFixedRelease() {
        var properties = new Properties();
        properties.setProperty("version", "9.8.7-test");
        var response = new SystemController(new BuildProperties(properties)).version();
        assertEquals("9.8.7-test", response.version());
        assertEquals("v1", response.apiVersion());
    }
}
