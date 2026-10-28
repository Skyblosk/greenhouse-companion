package dev.ilyapple.ghc.core.schematic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The decompress half of lz-string's {@code decompressFromEncodedURIComponent}, which SkyMutations layout links use
 * (Skyblocker ships the whole library as {@code de.hysky.skyblocker.utils.LZString}).
 */
final class LZString {
	private static final String URI_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-$";
	private static final Map<Character, Integer> REVERSE = new HashMap<>();

	static {
		for (int i = 0; i < URI_SAFE.length(); i++) REVERSE.put(URI_SAFE.charAt(i), i);
	}

	private LZString() {}

	static @Nullable String decompressFromEncodedURIComponent(@Nullable String input) {
		if (input == null) return "";
		if (input.isEmpty()) return null;
		String text = input.replace(' ', '+');
		return decompress(text.length(), 32, index -> {
			Integer value = REVERSE.get(text.charAt(index));
			return value == null ? 0 : value;
		});
	}

	private interface NextValue {
		int at(int index);
	}

	private static @Nullable String decompress(int length, int resetValue, NextValue next) {
		List<String> dictionary = new ArrayList<>();
		for (int i = 0; i < 3; i++) dictionary.add(String.valueOf((char) i));
		int enlargeIn = 4;
		int dictSize = 4;
		int numBits = 3;
		StringBuilder result = new StringBuilder();

		int[] data = {next.at(0), resetValue, 1};
		int bits = readBits(2, data, length, resetValue, next);
		String c;
		switch (bits) {
			case 0 -> c = String.valueOf((char) readBits(8, data, length, resetValue, next));
			case 1 -> c = String.valueOf((char) readBits(16, data, length, resetValue, next));
			case 2 -> {
				return "";
			}
			default -> {
				return null;
			}
		}
		dictionary.add(c);
		String w = c;
		result.append(c);

		while (true) {
			if (data[2] > length) return "";
			int cc = readBits(numBits, data, length, resetValue, next);
			switch (cc) {
				case 0 -> {
					dictionary.add(String.valueOf((char) readBits(8, data, length, resetValue, next)));
					cc = dictSize++;
					enlargeIn--;
				}
				case 1 -> {
					dictionary.add(String.valueOf((char) readBits(16, data, length, resetValue, next)));
					cc = dictSize++;
					enlargeIn--;
				}
				case 2 -> {
					return result.toString();
				}
				default -> {
				}
			}
			if (enlargeIn == 0) {
				enlargeIn = 1 << numBits;
				numBits++;
			}

			String entry;
			if (cc < dictionary.size() && dictionary.get(cc) != null) {
				entry = dictionary.get(cc);
			} else if (cc == dictSize) {
				entry = w + w.charAt(0);
			} else {
				return null;
			}
			result.append(entry);
			dictionary.add(w + entry.charAt(0));
			dictSize++;
			enlargeIn--;
			w = entry;
			if (enlargeIn == 0) {
				enlargeIn = 1 << numBits;
				numBits++;
			}
		}
	}

	/** Reads {@code count} bits; {@code data} is {value, position, index}. */
	private static int readBits(int count, int[] data, int length, int resetValue, NextValue next) {
		int bits = 0;
		int maxPower = 1 << count;
		int power = 1;
		while (power != maxPower) {
			int resb = data[0] & data[1];
			data[1] >>= 1;
			if (data[1] == 0) {
				data[1] = resetValue;
				data[0] = data[2] < length ? next.at(data[2]) : 0;
				data[2]++;
			}
			bits |= (resb > 0 ? 1 : 0) * power;
			power <<= 1;
		}
		return bits;
	}
}
