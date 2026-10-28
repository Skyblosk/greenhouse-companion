package dev.ilyapple.ghc.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MenuReaderTest {
	@Test
	void sackAmountsAndCountdownsParse() {
		assertEquals(1234, MenuReader.amount("1,234", ""));
		assertEquals(20_100, MenuReader.amount("20.1", "k"));
		assertEquals((3600 + 36 * 60 + 2) * 1000L, MenuReader.durationMs("1h 36m 2s"));
	}
}
