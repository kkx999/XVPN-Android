package com.xvpn.android;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SafeYamlTest {
    @Test public void yamlQuoteEscapesBackslashWithoutJsonSlashEscape() {
        String quoted = SafeYaml.quote("a\\b/c\"d\n#x:y");
        assertTrue(quoted.startsWith("\"") && quoted.endsWith("\""));
        assertTrue(quoted.contains("a\\\\b/c\\\"d\\n#x:y"));
        assertFalse(quoted.contains("\\/"));
    }
}
