package de.soderer.zip;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Buffered input stream wrapper, which also closes some other items (like a ZipFile the stream
 * belongs to) when it is closed.
 * <p>
 * A plain {@code null} as second constructor argument is ambiguous between the name and the items to
 * close and does not compile, use {@code (String) null} instead.
 * </p>
 */
public class InputStreamWithOtherItemsToClose extends InputStream {
	private final InputStream baseInputStream;
	private final String name;
	private final Closeable[] otherItemsToClose;

	/** True after {@link #close()} was called, so the items are closed only once */
	private boolean closed = false;

	/**
	 * Creates a new named stream.
	 *
	 * @param baseInputStream
	 *            the stream to read from, must not be null, it is wrapped by a {@link BufferedInputStream}
	 * @param name
	 *            a name for the stream, for example the name of a zip entry, may be null
	 * @param otherItemsToClose
	 *            items to close after the base stream was closed, null entries are ignored
	 * @throws IllegalArgumentException
	 *             if the base stream is null
	 */
	public InputStreamWithOtherItemsToClose(final InputStream baseInputStream, final String name, final Closeable... otherItemsToClose) {
		if (baseInputStream == null) {
			throw new IllegalArgumentException("Invalid empty baseInputStream");
		}
		this.baseInputStream = new BufferedInputStream(baseInputStream);
		this.name = name;
		// Copied, so later changes of the caller's array do not change the items to close
		this.otherItemsToClose = otherItemsToClose == null ? new Closeable[0] : otherItemsToClose.clone();
	}

	/**
	 * Creates a new stream without name.
	 *
	 * @param baseInputStream
	 *            the stream to read from, must not be null, it is wrapped by a {@link BufferedInputStream}
	 * @param otherItemsToClose
	 *            items to close after the base stream was closed, null entries are ignored
	 * @throws IllegalArgumentException
	 *             if the base stream is null
	 */
	public InputStreamWithOtherItemsToClose(final InputStream baseInputStream, final Closeable... otherItemsToClose) {
		this(baseInputStream, (String) null, otherItemsToClose);
	}

	/**
	 * Returns the name of this stream.
	 *
	 * @return the name, or null if no name was given
	 */
	public String getName() {
		return name;
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
	public long skip(final long n) throws IOException {
		return baseInputStream.skip(n);
	}

	@Override
	public int available() throws IOException {
		return baseInputStream.available();
	}

	/**
	 * Mark and reset are supported by the internal {@link BufferedInputStream}.
	 */
	@Override
	public boolean markSupported() {
		return baseInputStream.markSupported();
	}

	@Override
	public synchronized void mark(final int readlimit) {
		baseInputStream.mark(readlimit);
	}

	@Override
	public synchronized void reset() throws IOException {
		baseInputStream.reset();
	}

	/**
	 * Closes the base stream and all other items. All items are closed even if closing one of them
	 * fails, also with a RuntimeException; the first exception is rethrown afterwards, further
	 * exceptions are added as suppressed. Calling this method again has no effect.
	 *
	 * @throws IOException
	 *             if closing any of the items failed
	 */
	@Override
	public void close() throws IOException {
		if (closed) {
			return;
		}
		closed = true;

		Exception firstException = null;
		try {
			baseInputStream.close();
		} catch (final IOException | RuntimeException e) {
			firstException = e;
		}
		for (final Closeable otherItemToClose : otherItemsToClose) {
			if (otherItemToClose != null) {
				try {
					otherItemToClose.close();
				} catch (final IOException | RuntimeException e) {
					if (firstException == null) {
						firstException = e;
					} else {
						firstException.addSuppressed(e);
					}
				}
			}
		}

		if (firstException instanceof IOException) {
			throw (IOException) firstException;
		} else if (firstException instanceof RuntimeException) {
			throw (RuntimeException) firstException;
		}
	}
}
