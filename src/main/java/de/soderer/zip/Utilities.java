package de.soderer.zip;

import java.io.Closeable;
import java.io.IOException;


/**
 * Global Utilities
 *
 * <p>This class does no Logging via Log4J, because it is often used before its initialisation</p>
 */
final class Utilities {
	private Utilities() {
		// Utility class
	}

	/**
	 * Check whether a string is null, empty or contains only whitespace (characters up to U+0020, see {@link String#trim()}).
	 *
	 * @param value string to check
	 * @return true for null, empty or whitespace only
	 */
	public static boolean isBlank(final String value) {
		return value == null || value.length() == 0 || value.trim().length() == 0;
	}

	/**
	 * Close a resource and ignore any IOException.
	 *
	 * @param closeable resource to close, may be null
	 */
	public static void closeQuietly(final Closeable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (@SuppressWarnings("unused") final IOException e) {
				// Do nothing
			}
		}
	}
}
