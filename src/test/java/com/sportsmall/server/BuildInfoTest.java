package com.sportsmall.server;

import org.junit.jupiter.api.Test;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class BuildInfoTest {
    @Test void healthUsesTheBuiltProjectVersionAndAnActualUtcBuildTime() throws Exception {
        var pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        String version = pom.getDocumentElement().getElementsByTagName("version").item(0).getTextContent();
        var info = BuildInfo.current();
        assertEquals(version, info.health().get("version"));
        assertDoesNotThrow(() -> Instant.parse(info.builtAt()));
        assertEquals("sports-mall", info.health().get("application"));
        assertEquals("UP", info.health().get("status"));
        assertEquals(4, info.health().size());
    }
}
