package de.soderer.zip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests of {@link InputStreamWithOtherItemsToClose} and the package internal {@link Utilities}.
 */
@SuppressWarnings("static-method")
public class InputStreamWithOtherItemsToCloseTest {
	private static final byte[] DATA = new byte[] { 1, 2, 3, 4, 5, 6 };

	/**
	 * Closeable recording its close calls, optionally failing.
	 */
	private static class RecordingCloseable implements Closeable {
		private final List<String> closeLog;
		private final String name;
		private final Exception failure;

		RecordingCloseable(final List<String> closeLog, final String name, final Exception failure) {
			this.closeLog = closeLog;
			this.name = name;
			this.failure = failure;
		}

		@Override
		public void close() throws IOException {
			closeLog.add(name);
			if (failure instanceof IOException) {
				throw (IOException) failure;
			} else if (failure instanceof RuntimeException) {
				throw (RuntimeException) failure;
			}
		}
	}

	/**
	 * Input stream with a failing close method.
	 */
	private static InputStream failingCloseStream(final Exception failure) {
		return new ByteArrayInputStream(DATA) {
			@Override
			public void close() throws IOException {
				if (failure instanceof IOException) {
					throw (IOException) failure;
				}
				throw (RuntimeException) failure;
			}
		};
	}

	@Test
	public void testReadingDelegatesToBaseStream() throws Exception {
		try (InputStreamWithOtherItemsToClose inputStream = new InputStreamWithOtherItemsToClose(new ByteArrayInputStream(DATA), "name")) {
			assertEquals("name", inputStream.getName());
			assertEquals(6, inputStream.available());
			assertEquals(1, inputStream.read());
			assertEquals(1, inputStream.skip(1));
			assertTrue(inputStream.markSupported());
			inputStream.mark(10);
			final byte[] buffer = new byte[2];
			assertEquals(2, inputStream.read(buffer, 0, 2));
			assertArrayEquals(new byte[] { 3, 4 }, buffer);
			inputStream.reset();
			assertArrayEquals(new byte[] { 3, 4, 5, 6 }, inputStream.readAllBytes());
			assertEquals(-1, inputStream.read());
		}
	}

	@Test
	public void testConstructorWithoutName() throws Exception {
		try (InputStreamWithOtherItemsToClose inputStream = new InputStreamWithOtherItemsToClose(new ByteArrayInputStream(DATA))) {
			assertNull(inputStream.getName());
		}
		try (InputStreamWithOtherItemsToClose inputStream = new InputStreamWithOtherItemsToClose(new ByteArrayInputStream(DATA), "name", (Closeable[]) null)) {
			assertArrayEquals(DATA, inputStream.readAllBytes());
		}
		assertThrows(IllegalArgumentException.class, () -> new InputStreamWithOtherItemsToClose(null));
	}

	@Test
	public void testAllItemsAreClosedOnce() throws Exception {
		final List<String> closeLog = new ArrayList<>();
		final Closeable[] items = new Closeable[] { new RecordingCloseable(closeLog, "first", null), null, new RecordingCloseable(closeLog, "second", null) };
		try (final InputStreamWithOtherItemsToClose inputStream = new InputStreamWithOtherItemsToClose(new ByteArrayInputStream(DATA), items)) {
			// Later changes of the array do not change the items to close
			items[0] = new RecordingCloseable(closeLog, "replaced", null);

			inputStream.close();
			inputStream.close();
			assertEquals(List.of("first", "second"), closeLog);
		}
	}

	@Test
	public void testFirstIOExceptionIsThrownAndOthersAreSuppressed() {
		final List<String> closeLog = new ArrayList<>();
		final IOException baseFailure = new IOException("base");
		final InputStreamWithOtherItemsToClose inputStream = new InputStreamWithOtherItemsToClose(failingCloseStream(baseFailure),
				new RecordingCloseable(closeLog, "first", new IOException("first")),
				new RecordingCloseable(closeLog, "second", new IllegalStateException("second")));

		final IOException exception = assertThrows(IOException.class, () -> inputStream.close());
		assertSame(baseFailure, exception);
		assertEquals(2, exception.getSuppressed().length);
		// All items are closed in spite of the failures
		assertEquals(List.of("first", "second"), closeLog);
	}

	@Test
	public void testRuntimeExceptionOfOtherItemIsThrown() {
		final List<String> closeLog = new ArrayList<>();
		final InputStreamWithOtherItemsToClose inputStream = new InputStreamWithOtherItemsToClose(new ByteArrayInputStream(DATA),
				new RecordingCloseable(closeLog, "first", new IllegalStateException("first")));
		assertEquals("first", assertThrows(IllegalStateException.class, () -> inputStream.close()).getMessage());
	}

	@Test
	public void testRuntimeExceptionOfBaseStreamIsThrown() {
		final InputStreamWithOtherItemsToClose inputStream = new InputStreamWithOtherItemsToClose(failingCloseStream(new IllegalStateException("base")));
		assertEquals("base", assertThrows(IllegalStateException.class, () -> inputStream.close()).getMessage());
	}

	@Test
	public void testUtilities() {
		assertTrue(Utilities.isBlank(null));
		assertTrue(Utilities.isBlank(""));
		assertTrue(Utilities.isBlank(" \t"));
		assertEquals(false, Utilities.isBlank(" a "));

		final List<String> closeLog = new ArrayList<>();
		Utilities.closeQuietly(null);
		Utilities.closeQuietly(new RecordingCloseable(closeLog, "item", null));
		// IOExceptions are ignored
		Utilities.closeQuietly(new RecordingCloseable(closeLog, "failing", new IOException("failing")));
		assertEquals(List.of("item", "failing"), closeLog);
	}
}
