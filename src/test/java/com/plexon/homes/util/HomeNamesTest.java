package com.plexon.homes.util;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class HomeNamesTest {
    @Test void normalizesCanonicalIds() { assertEquals("home-2", HomeNames.normalize("Home-2", 24).orElseThrow()); }
    @Test void rejectsFormattingAndSpaces() { assertTrue(HomeNames.normalize("my home", 24).isEmpty()); assertTrue(HomeNames.normalize("<red>home", 24).isEmpty()); }
    @Test void enforcesLength() { assertTrue(HomeNames.normalize("abcdefghijklmnopqrstuvwxy", 24).isEmpty()); }
}
