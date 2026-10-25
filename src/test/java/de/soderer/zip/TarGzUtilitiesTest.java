package de.soderer.zip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TarGzUtilitiesTest {
	private static final byte[] DATA = "Content of the test file".getBytes(StandardCharsets.UTF_8);

	@TempDir
	File tempDirectory;

	/**
	 * Entry of a test archive.
	 */
	private static final class Entry {
		final String name;
		final byte linkFlag;
		final String linkName;
		final byte[] data;
		final int mode;

		Entry(final String name, final byte linkFlag, final String linkName, final byte[] data, final int mode) {
			this.name = name;
			this.linkFlag = linkFlag;
			this.linkName = linkName;
			this.data = data;
			this.mode = mode;
		}
	}

	private static Entry file(final String name, final byte[] data) {
		return new Entry(name, TarConstants.LF_NORMAL, null, data, 0644);
	}

	private static Entry file(final String name, final byte[] data, final int mode) {
		return new Entry(name, TarConstants.LF_NORMAL, null, data, mode);
	}

	private static Entry directory(final String name) {
		return new Entry(name, TarConstants.LF_DIR, null, null, 0755);
	}

	private static Entry symbolicLink(final String name, final String target) {
		return new Entry(name, TarConstants.LF_SYMLINK, target, null, 0777);
	}

	private static Entry hardLink(final String name, final String target) {
		return new Entry(name, TarConstants.LF_LINK, target, null, 0644);
	}

	private static Entry special(final String name, final byte linkFlag) {
		return new Entry(name, linkFlag, null, null, 0644);
	}

	/**
	 * Creates a tar.gz file with the given entries.
	 */
	private File createTarGz(final String fileName, final Entry... entries) throws IOException {
		final File tarGzFile = new File(tempDirectory, fileName);
		try (TarArchiveOutputStream tarOutputStream = new TarArchiveOutputStream(new GzipCompressorOutputStream(new FileOutputStream(tarGzFile)))) {
			tarOutputStream.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
			for (final Entry entry : entries) {
				final TarArchiveEntry tarEntry = new TarArchiveEntry(entry.name, entry.linkFlag, true);
				tarEntry.setMode(entry.mode | (entry.linkFlag == TarConstants.LF_DIR ? 040000 : 0100000));
				if (entry.linkName != null) {
					tarEntry.setLinkName(entry.linkName);
				}
				if (entry.data != null) {
					tarEntry.setSize(entry.data.length);
				}
				tarOutputStream.putArchiveEntry(tarEntry);
				if (entry.data != null) {
					tarOutputStream.write(entry.data);
				}
				tarOutputStream.closeArchiveEntry();
			}
		}
		return tarGzFile;
	}

	private File createSourceFile() throws IOException {
		final File sourceFile = new File(tempDirectory, "source.txt");
		Files.write(sourceFile.toPath(), DATA);
		return sourceFile;
	}

	private static boolean supportsSymbolicLinks(final File directory) {
		final File testLink = new File(directory, "symbolicLinkTest");
		try {
			Files.createSymbolicLink(testLink.toPath(), directory.toPath());
			Files.delete(testLink.toPath());
			return true;
		} catch (@SuppressWarnings("unused") final Exception e) {
			return false;
		}
	}

	// Compression

	@Test
	public void testCompressAndRead() throws Exception {
		final File tarGzFile = new File(tempDirectory, "test.tar.gz");
		TarGzUtilities.compress(tarGzFile, createSourceFile());

		assertEquals(DATA.length, TarGzUtilities.getUncompressedSize(tarGzFile));
		assertEquals(1, TarGzUtilities.getFilesCount(tarGzFile));
		try (InputStream inputStream = TarGzUtilities.openCompressedFile(tarGzFile)) {
			assertArrayEquals(DATA, inputStream.readAllBytes());
		}
		try (InputStream inputStream = TarGzUtilities.openCompressedFile(tarGzFile, "source.txt")) {
			assertArrayEquals(DATA, inputStream.readAllBytes());
		}

		final File destination = new File(tempDirectory, "destination");
		TarGzUtilities.decompress(tarGzFile, destination);
		assertArrayEquals(DATA, Files.readAllBytes(new File(destination, "source.txt").toPath()));
	}

	@Test
	public void testCompressWithLongPathInArchive() throws Exception {
		// Longer than the 100 characters of the classic tar format
		final String longPath = "dir/" + "x".repeat(150) + ".txt";
		final File tarGzFile = new File(tempDirectory, "test.tar.gz");
		TarGzUtilities.compress(tarGzFile, createSourceFile(), longPath);
		try (InputStream inputStream = TarGzUtilities.openCompressedFile(tarGzFile, longPath)) {
			assertArrayEquals(DATA, inputStream.readAllBytes());
		}
	}

	@Test
	public void testCompressErrors() throws Exception {
		final File sourceFile = createSourceFile();
		final File existingFile = new File(tempDirectory, "existing.tar.gz");
		Files.write(existingFile.toPath(), DATA);

		assertThrows(IOException.class, () -> TarGzUtilities.compress(existingFile, sourceFile));
		assertThrows(IOException.class, () -> TarGzUtilities.compress(new File(tempDirectory, "missing/test.tar.gz"), sourceFile));
		assertThrows(IOException.class, () -> TarGzUtilities.compress(new File(tempDirectory, "test.tar.gz"), new File(tempDirectory, "missing.txt")));
		assertThrows(IOException.class, () -> TarGzUtilities.compress(new File(tempDirectory, "test.tar.gz"), tempDirectory));
		assertFalse(new File(tempDirectory, "test.tar.gz").exists());
	}

	// Reading

	@Test
	public void testSizeAndCountOnlyIncludeRegularFiles() throws Exception {
		final File tarGzFile = createTarGz("test.tar.gz",
				directory("dir/"),
				file("dir/a.txt", DATA),
				file("b.txt", new byte[10]),
				symbolicLink("link", "b.txt"),
				hardLink("hard", "b.txt"),
				special("fifo", TarConstants.LF_FIFO));
		assertEquals(DATA.length + 10, TarGzUtilities.getUncompressedSize(tarGzFile));
		assertEquals(2, TarGzUtilities.getFilesCount(tarGzFile));
	}

	@Test
	public void testReadingErrors() throws Exception {
		final File missingFile = new File(tempDirectory, "missing.tar.gz");
		final File noGzipFile = new File(tempDirectory, "invalid.tar.gz");
		Files.write(noGzipFile.toPath(), DATA);

		for (final File invalidFile : new File[] { missingFile, tempDirectory }) {
			assertThrows(Exception.class, () -> TarGzUtilities.getUncompressedSize(invalidFile));
			assertThrows(Exception.class, () -> TarGzUtilities.getFilesCount(invalidFile));
			assertThrows(Exception.class, () -> TarGzUtilities.openCompressedFile(invalidFile));
			assertThrows(Exception.class, () -> TarGzUtilities.decompress(invalidFile, new File(tempDirectory, "destination")));
		}

		assertTrue(assertThrows(Exception.class, () -> TarGzUtilities.getUncompressedSize(noGzipFile)).getMessage().startsWith("Cannot read"));
		assertTrue(assertThrows(Exception.class, () -> TarGzUtilities.getFilesCount(noGzipFile)).getMessage().startsWith("Cannot read"));
		assertThrows(IOException.class, () -> TarGzUtilities.openCompressedFile(noGzipFile));
	}

	@Test
	public void testOpenCompressedFileByName() throws Exception {
		final File tarGzFile = createTarGz("test.tar.gz",
				directory("./dir/"),
				file("./dir/a.txt", DATA),
				file("b.txt", new byte[] { 1 }));
		// Leading "./" and backslashes are ignored for the comparison
		try (InputStream inputStream = TarGzUtilities.openCompressedFile(tarGzFile, "dir\\a.txt")) {
			assertArrayEquals(DATA, inputStream.readAllBytes());
		}
		try (InputStream inputStream = TarGzUtilities.openCompressedFile(tarGzFile, "./b.txt")) {
			assertArrayEquals(new byte[] { 1 }, inputStream.readAllBytes());
		}
		// The first file without name
		try (InputStream inputStream = TarGzUtilities.openCompressedFile(tarGzFile, " ")) {
			assertArrayEquals(DATA, inputStream.readAllBytes());
		}
		assertTrue(assertThrows(Exception.class, () -> TarGzUtilities.openCompressedFile(tarGzFile, "c.txt")).getMessage().contains("does not contain file"));
		assertTrue(assertThrows(Exception.class, () -> TarGzUtilities.openCompressedFile(createTarGz("empty.tar.gz", directory("dir/")))).getMessage().contains("is empty"));
	}

	// Decompression

	@Test
	public void testDecompressAllEntryTypes() throws Exception {
		final boolean symbolicLinks = supportsSymbolicLinks(tempDirectory);
		final File tarGzFile = createTarGz("test.tar.gz",
				directory("dir/"),
				directory("dir/"),
				file("dir/a.txt", DATA, 0750),
				file("implicit/parent/b.txt", new byte[] { 2 }),
				hardLink("dir/hard.txt", "dir/a.txt"),
				symbolicLink("link.txt", "dir/a.txt"),
				symbolicLink("dir/up.txt", "../implicit/parent/b.txt"),
				special("fifo", TarConstants.LF_FIFO),
				special("char", TarConstants.LF_CHR),
				special("block", TarConstants.LF_BLK));
		final File destination = new File(tempDirectory, "destination");
		TarGzUtilities.decompress(tarGzFile, destination);

		assertArrayEquals(DATA, Files.readAllBytes(new File(destination, "dir/a.txt").toPath()));
		assertArrayEquals(new byte[] { 2 }, Files.readAllBytes(new File(destination, "implicit/parent/b.txt").toPath()));
		assertArrayEquals(DATA, Files.readAllBytes(new File(destination, "dir/hard.txt").toPath()));
		assertArrayEquals(DATA, Files.readAllBytes(new File(destination, "link.txt").toPath()));
		assertArrayEquals(new byte[] { 2 }, Files.readAllBytes(new File(destination, "dir/up.txt").toPath()));
		if (symbolicLinks) {
			assertTrue(Files.isSymbolicLink(new File(destination, "link.txt").toPath()));
		}
		// Special files are skipped
		assertFalse(new File(destination, "fifo").exists());
		assertFalse(new File(destination, "char").exists());

		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(new File(destination, "dir/a.txt").toPath());
			assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE), permissions);
		}
	}

	@Test
	public void testDecompressKeepsFilesWritableForOwner() throws Exception {
		final File tarGzFile = createTarGz("test.tar.gz", file("readonly.txt", DATA, 0444));
		final File destination = new File(tempDirectory, "destination");
		TarGzUtilities.decompress(tarGzFile, destination);
		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			assertTrue(Files.getPosixFilePermissions(new File(destination, "readonly.txt").toPath()).contains(PosixFilePermission.OWNER_WRITE));
		}
	}

	@Test
	public void testDecompressToExistingDestination() throws Exception {
		final File tarGzFile = createTarGz("test.tar.gz", file("a.txt", DATA));
		assertTrue(assertThrows(Exception.class, () -> TarGzUtilities.decompress(tarGzFile, tempDirectory)).getMessage().contains("already exists"));
	}

	/**
	 * Decompresses an archive, which must fail, and checks that the destination is removed completely.
	 */
	private void assertDecompressionFails(final String expectedCauseMessagePart, final Entry... entries) throws Exception {
		final File tarGzFile = createTarGz("failing.tar.gz", entries);
		final File destination = new File(tempDirectory, "destination");
		final Exception exception = assertThrows(Exception.class, () -> TarGzUtilities.decompress(tarGzFile, destination));
		assertTrue(exception.getMessage().startsWith("Cannot decompress"), exception.getMessage());
		assertTrue(exception.getCause().getMessage().contains(expectedCauseMessagePart), exception.getCause().getMessage());
		assertFalse(destination.exists());
		tarGzFile.delete();
	}

	@Test
	public void testDecompressWithFileInTheWayOfDirectories() throws Exception {
		// Directories below a file of the archive cannot be created
		assertDecompressionFails("Cannot create directory", file("file", DATA), directory("file/sub/"));
		assertDecompressionFails("Cannot create directory", file("file", DATA), file("file/sub/a.txt", DATA));

		// The destination directory cannot be created below a file
		final File tarGzFile = createTarGz("test.tar.gz", file("a.txt", DATA));
		final File someFile = createSourceFile();
		final Exception exception = assertThrows(Exception.class, () -> TarGzUtilities.decompress(tarGzFile, new File(someFile, "destination")));
		assertTrue(exception.getCause().getMessage().contains("Cannot create destination directory"), exception.getCause().getMessage());
	}

	@Test
	public void testDecompressRejectsPathTraversal() throws Exception {
		assertDecompressionFails("Traversal error", file("ok.txt", DATA), file("../evil.txt", DATA));
		assertDecompressionFails("Traversal error", file("/absolute.txt", DATA));
		assertDecompressionFails("Traversal error", file("dir/../../evil.txt", DATA));
		assertDecompressionFails("Traversal error", directory("dir/.."));
		assertDecompressionFails("Traversal error", file("..", DATA));
		assertDecompressionFails("Traversal error", hardLink("hard.txt", "../outside.txt"));
		assertFalse(new File(tempDirectory, "evil.txt").exists());
	}

	@Test
	public void testDecompressRejectsInvalidLinks() throws Exception {
		assertDecompressionFails("not found", hardLink("hard.txt", "missing.txt"));
		assertDecompressionFails("Traversal error", symbolicLink("link", "/etc/passwd"));
		assertDecompressionFails("Traversal error", symbolicLink("dir/link", "../../outside"));
	}

	@Test
	public void testDecompressRejectsSymbolicLinksResolvingOutside() throws Exception {
		if (!supportsSymbolicLinks(tempDirectory)) {
			return;
		}
		// Lexically inside, but "self/.." resolves to the parent of the destination after creating "self"
		assertDecompressionFails("Traversal error", symbolicLink("self", "."), symbolicLink("escape", "self/.."));
		assertDecompressionFails("does not resolve", symbolicLink("dangling", "missing.txt"));
		// A symbolic link at the path of a file of the archive
		assertDecompressionFails("", file("a.txt", DATA), symbolicLink("a.txt", "b.txt"), file("b.txt", DATA));
	}
}
