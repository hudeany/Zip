package de.soderer.zip;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Buffered input stream wrapper, which also closes some other items (like a ZipFile the stream
 * belongs to) when it is closed.
 */
final class InputStreamWithOtherItemsToClose extends InputStream {
	private InputStream baseInputStream = null;
	private Closeable[] otherItemsToClose = null;

	/**
	 * Creates a new stream without name.
	 *
	 * @param baseInputStream
	 *            the stream to read from, it is wrapped by a {@link BufferedInputStream}
	 * @param otherItemsToClose
	 *            items to close after the base stream was closed, null entries are ignored
	 */
	public InputStreamWithOtherItemsToClose(final InputStream baseInputStream, final Closeable... otherItemsToClose) {
		this.baseInputStream = new BufferedInputStream(baseInputStream);
		this.otherItemsToClose = otherItemsToClose;
	}

	@Override
	public int read() throws IOException {
		return baseInputStream.read();
	}

	@Override
	public int read(final byte[] bts, final int off, final int len) throws IOException {
		return baseInputStream.read(bts, off, len);
	}

	@Override
	public int available() throws IOException {
		return baseInputStream.available();
	}

	/**
	 * Closes the base stream and all other items. All items are closed even if closing one of them
	 * fails; the first exception is rethrown afterwards, further exceptions are added as suppressed.
	 *
	 * @throws IOException
	 *             if closing any of the items failed
	 */
	@Override
	public void close() throws IOException {
		IOException firstException = null;
		try {
			baseInputStream.close();
		} catch (final IOException e) {
			firstException = e;
		}
		if (otherItemsToClose != null) {
			for (final Closeable otherItemToClose : otherItemsToClose) {
				if (otherItemToClose != null) {
					try {
						otherItemToClose.close();
					} catch (final IOException e) {
						if (firstException == null) {
							firstException = e;
						} else {
							firstException.addSuppressed(e);
						}
					}
				}
			}
		}
		if (firstException != null) {
			throw firstException;
		}
	}
}
