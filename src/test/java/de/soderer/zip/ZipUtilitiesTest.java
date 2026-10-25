package de.soderer.zip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("static-method")
public class ZipUtilitiesTest {
	private static final byte[] DATA_A = "Content of file a".getBytes(StandardCharsets.UTF_8);
	private static final byte[] DATA_B = "Content of file b".getBytes(StandardCharsets.UTF_8);
	private static final Charset CP437 = Charset.forName("Cp437");

	@TempDir
	File tempDirectory;

	/** Directory "source" with a.txt, sub/b.txt and the empty directory empty */
	private File sourceDirectory;

	@BeforeEach
	public void setup() throws Exception {
		sourceDirectory = new File(tempDirectory, "source");
		new File(sourceDirectory, "sub").mkdirs();
		new File(sourceDirectory, "empty").mkdirs();
		Files.write(new File(sourceDirectory, "a.txt").toPath(), DATA_A);
		Files.write(new File(sourceDirectory, "sub/b.txt").toPath(), DATA_B);
	}

	/**
	 * Creates a zip file with the given entries, entry names ending with "/" are directories.
	 */
	private File createZip(final String fileName, final Charset charset, final String... entryNames) throws IOException {
		final File zipFile = new File(tempDirectory, fileName);
		try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(zipFile), charset)) {
			for (final String entryName : entryNames) {
				zipOutputStream.putNextEntry(new ZipEntry(entryName));
				if (!entryName.endsWith("/")) {
					zipOutputStream.write(entryName.getBytes(StandardCharsets.UTF_8));
				}
				zipOutputStream.closeEntry();
			}
		}
		return zipFile;
	}

	private File createZip(final String fileName, final String... entryNames) throws IOException {
		return createZip(fileName, StandardCharsets.UTF_8, entryNames);
	}

	private static Set<String> listRecursively(final File directory) {
		final Set<String> entries = new TreeSet<>();
		listRecursively(directory, "", entries);
		return entries;
	}

	private static void listRecursively(final File directory, final String prefix, final Set<String> entries) {
		final File[] subFiles = directory.listFiles();
		if (subFiles != null) {
			for (final File subFile : subFiles) {
				entries.add(prefix + subFile.getName() + (subFile.isDirectory() ? "/" : ""));
				if (subFile.isDirectory()) {
					listRecursively(subFile, prefix + subFile.getName() + "/", entries);
				}
			}
		}
	}

	// Creating zip files

	@Test
	public void testZipDirectoryAndUnzip() throws Exception {
		final File zipFile = ZipUtilities.zipFile(sourceDirectory);
		assertEquals(new File(sourceDirectory.getAbsolutePath() + ".zip"), zipFile);
		assertTrue(ZipUtilities.isZipArchiveFile(zipFile));
		assertEquals(Set.of("source/a.txt", "source/sub/b.txt", "source/empty/"), new TreeSet<>(ZipUtilities.getZipFileEntries(zipFile)));
		assertEquals(DATA_A.length + DATA_B.length, ZipUtilities.getDataSizeUncompressed(zipFile));

		final File destination = new File(tempDirectory, "destination");
		destination.mkdir();
		ZipUtilities.unzipFile(zipFile, destination);
		assertEquals(Set.of("source/", "source/a.txt", "source/empty/", "source/sub/", "source/sub/b.txt"), listRecursively(destination));
		assertArrayEquals(DATA_B, Files.readAllBytes(new File(destination, "source/sub/b.txt").toPath()));
	}

	@Test
	public void testZipSingleFileWithCharset() throws Exception {
		final File zipFile = new File(tempDirectory, "single.zip");
		ZipUtilities.zipFile(new File(sourceDirectory, "a.txt"), zipFile, CP437);
		assertEquals(List.of("a.txt"), ZipUtilities.getZipFileEntries(zipFile));

		final File destination = new File(tempDirectory, "destination");
		destination.mkdir();
		ZipUtilities.unzipFile(zipFile, destination, CP437);
		assertArrayEquals(DATA_A, Files.readAllBytes(new File(destination, "a.txt").toPath()));
	}

	@Test
	public void testReadingLegacyEncodedEntryNames() throws Exception {
		// The Cp437 encoded name is no valid UTF-8, so the legacy charset is used for reading.
		// The umlaut only occurs in the zip file, so the test also works on file systems with ASCII file names.
		final File zipFile = createZip("legacy.zip", CP437, "Ä.txt");
		assertEquals(List.of("Ä.txt"), ZipUtilities.getZipFileEntries(zipFile));
		assertArrayEquals("Ä.txt".getBytes(StandardCharsets.UTF_8), ZipUtilities.readExistingZipFile(zipFile).get("Ä.txt"));
		try (InputStream inputStream = ZipUtilities.openZipFile(zipFile.getAbsolutePath(), "Ä.txt")) {
			assertArrayEquals("Ä.txt".getBytes(StandardCharsets.UTF_8), inputStream.readAllBytes());
		}
	}

	@Test
	public void testZipFileErrors() throws Exception {
		final File zipFile = new File(tempDirectory, "existing.zip");
		Files.write(zipFile.toPath(), new byte[0]);

		assertThrows(IOException.class, () -> ZipUtilities.zipFile(new File(tempDirectory, "missing")));
		assertThrows(IOException.class, () -> ZipUtilities.zipFile(sourceDirectory, zipFile, null));
		// The zip file must not be part of its own content
		final IOException exception = assertThrows(IOException.class, () -> ZipUtilities.zipFile(sourceDirectory, new File(sourceDirectory, "inside.zip"), null));
		assertTrue(exception.getMessage().contains("must not be located inside the source directory"));
		assertFalse(new File(sourceDirectory, "inside.zip").exists());
	}

	@Test
	public void testZipDirectoryWithCycle() throws Exception {
		try {
			Files.createSymbolicLink(new File(sourceDirectory, "sub/loop").toPath(), sourceDirectory.toPath());
		} catch (@SuppressWarnings("unused") final Exception e) {
			// Symbolic links are not available on this system
			return;
		}
		final File zipFile = new File(tempDirectory, "cycle.zip");
		assertTrue(assertThrows(IOException.class, () -> ZipUtilities.zipFile(sourceDirectory, zipFile, null)).getMessage().contains("Directory cycle detected"));
		// An incomplete zip file is removed
		assertFalse(zipFile.exists());
	}

	@Test
	public void testIsZipArchiveFile() throws Exception {
		final File textFile = new File(tempDirectory, "text.txt");
		Files.write(textFile.toPath(), DATA_A);
		final File shortFile = new File(tempDirectory, "short.bin");
		Files.write(shortFile.toPath(), new byte[] { 0x50, 0x4B });

		assertTrue(ZipUtilities.isZipArchiveFile(createZip("test.zip", "a.txt")));
		assertFalse(ZipUtilities.isZipArchiveFile(textFile));
		assertFalse(ZipUtilities.isZipArchiveFile(shortFile));
	}

	// Zip output streams

	@Test
	public void testAddFilesAndDataToOpenStream() throws Exception {
		final File zipFile = new File(tempDirectory, "stream.zip");
		try (ZipOutputStream zipOutputStream = ZipUtilities.openNewZipOutputStream(zipFile, StandardCharsets.UTF_8)) {
			ZipUtilities.addFileToOpenZipFileStream(new File(sourceDirectory, "a.txt"), "\\dir1\\dir2", zipOutputStream);
			ZipUtilities.addFileToOpenZipFileStream(new File(sourceDirectory, "sub"), "/dir3/", zipOutputStream);
			ZipUtilities.addFileDataToOpenZipFileStream(DATA_B, "data.txt", zipOutputStream);
			ZipUtilities.addFileDataToOpenZipFileStream(DATA_B, "\\data\\", "more.txt", zipOutputStream);
		}

		final Map<String, byte[]> content = ZipUtilities.readExistingZipFile(zipFile);
		assertEquals(Set.of("dir1/dir2/a.txt", "dir3/sub/b.txt", "data.txt", "data/more.txt"), content.keySet());
		assertArrayEquals(DATA_A, content.get("dir1/dir2/a.txt"));
		assertArrayEquals(DATA_B, content.get("data/more.txt"));
	}

	@Test
	public void testOpenNewZipOutputStreamOnStream() throws Exception {
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ZipOutputStream zipOutputStream = ZipUtilities.openNewZipOutputStream(output)) {
			ZipUtilities.addFileDataToOpenZipFileStream(DATA_A, "a.txt", zipOutputStream);
		}
		try (ZipInputStream zipInputStream = new ZipInputStream(new java.io.ByteArrayInputStream(output.toByteArray()))) {
			assertEquals("a.txt", zipInputStream.getNextEntry().getName());
			assertArrayEquals(DATA_A, zipInputStream.readAllBytes());
		}
		assertThrows(IOException.class, () -> ZipUtilities.openNewZipOutputStream(null));
	}

	@Test
	public void testOpenStreamErrors() throws Exception {
		final File existingFile = createZip("existing.zip", "a.txt");
		assertThrows(IOException.class, () -> ZipUtilities.openNewZipOutputStream(existingFile, null));
		assertThrows(IOException.class, () -> ZipUtilities.openNewZipOutputStream(new File(tempDirectory, "missing/new.zip"), null));

		try (ZipOutputStream zipOutputStream = ZipUtilities.openNewZipOutputStream(new File(tempDirectory, "new.zip"), null)) {
			assertThrows(IOException.class, () -> ZipUtilities.addFileToOpenZipFileStream(new File(tempDirectory, "missing"), zipOutputStream));
			assertThrows(IOException.class, () -> ZipUtilities.addFileToOpenZipFileStream(new File(sourceDirectory, "a.txt"), null));

			assertThrows(IOException.class, () -> ZipUtilities.addFileDataToOpenZipFileStream(null, "a.txt", zipOutputStream));
			assertThrows(IOException.class, () -> ZipUtilities.addFileDataToOpenZipFileStream(DATA_A, " ", zipOutputStream));
			assertThrows(IOException.class, () -> ZipUtilities.addFileDataToOpenZipFileStream(DATA_A, "a.txt", null));
			assertThrows(IOException.class, () -> ZipUtilities.addFileDataToOpenZipFileStream(DATA_A, null, "a.txt", zipOutputStream));
			// A relative directory path must end with a separator
			assertThrows(IOException.class, () -> ZipUtilities.addFileDataToOpenZipFileStream(DATA_A, "dir", "a.txt", zipOutputStream));
		}
	}

	// Changing existing zip files

	@Test
	public void testAddFileToExistingZipFile() throws Exception {
		final File zipFile = createZip("existing.zip", "first.txt", "dir/");
		ZipUtilities.addFileToExistingZipFile(new File(sourceDirectory, "a.txt"), zipFile);
		ZipUtilities.addFileToExistingZipFile(List.of(new File(sourceDirectory, "sub")), zipFile);

		assertEquals(List.of("first.txt", "dir/", "a.txt", "sub/b.txt"), ZipUtilities.getZipFileEntries(zipFile));
		assertArrayEquals("first.txt".getBytes(StandardCharsets.UTF_8), ZipUtilities.readExistingZipFile(zipFile).get("first.txt"));
		// No temp files remain
		assertEquals(Set.of("existing.zip", "source/"), Set.copyOf(List.of(tempDirectory.list()).stream().map(name -> new File(tempDirectory, name).isDirectory() ? name + "/" : name).toList()));
	}

	@Test
	public void testAddFileToExistingZipFileErrors() throws Exception {
		final File zipFile = createZip("existing.zip", "first.txt");
		assertThrows(IOException.class, () -> ZipUtilities.addFileToExistingZipFile(new File(sourceDirectory, "a.txt"), new File(tempDirectory, "missing.zip")));
		assertThrows(IOException.class, () -> ZipUtilities.addFileToExistingZipFile(new File(tempDirectory, "missing.txt"), zipFile));

		final File zipInsideSource = createZip("../" + tempDirectory.getName() + "/source/inside.zip", "first.txt");
		assertThrows(IOException.class, () -> ZipUtilities.addFileToExistingZipFile(sourceDirectory, zipInsideSource));

		// An invalid zip file is not changed and no temp file remains
		final File invalidZipFile = new File(tempDirectory, "invalid.zip");
		Files.write(invalidZipFile.toPath(), DATA_A);
		assertThrows(IOException.class, () -> ZipUtilities.addFileToExistingZipFile(new File(sourceDirectory, "a.txt"), invalidZipFile));
		assertArrayEquals(DATA_A, Files.readAllBytes(invalidZipFile.toPath()));
		assertEquals(3, tempDirectory.list().length);
	}

	@Test
	public void testOpenExistingZipFileForExtension() throws Exception {
		final File zipFile = createZip("existing.zip", "first.txt");
		try (ZipOutputStream zipOutputStream = ZipUtilities.openExistingZipFileForExtensionOrCreateNewZipFile(zipFile)) {
			ZipUtilities.addFileDataToOpenZipFileStream(DATA_A, "second.txt", zipOutputStream);
		}
		assertEquals(List.of("first.txt", "second.txt"), ZipUtilities.getZipFileEntries(zipFile));

		final File newZipFile = new File(tempDirectory, "new.zip");
		try (ZipOutputStream zipOutputStream = ZipUtilities.openExistingZipFileForExtensionOrCreateNewZipFile(newZipFile)) {
			ZipUtilities.addFileDataToOpenZipFileStream(DATA_A, "only.txt", zipOutputStream);
		}
		assertEquals(List.of("only.txt"), ZipUtilities.getZipFileEntries(newZipFile));
	}

	@Test
	public void testOpenExistingZipFileForExtensionErrors() throws Exception {
		assertThrows(IOException.class, () -> ZipUtilities.openExistingZipFileForExtension(new File(tempDirectory, "missing.zip")));

		// An invalid zip file is restored
		final File invalidZipFile = new File(tempDirectory, "invalid.zip");
		Files.write(invalidZipFile.toPath(), DATA_A);
		assertThrows(IOException.class, () -> ZipUtilities.openExistingZipFileForExtension(invalidZipFile));
		assertArrayEquals(DATA_A, Files.readAllBytes(invalidZipFile.toPath()));
		assertEquals(2, tempDirectory.list().length);
	}

	@Test
	public void testRemoveFilesFromZipFile() throws Exception {
		final File zipFile = createZip("remove.zip", "keep.txt", "remove.log", "dir/remove.txt", "dir/keep.csv", "a+b.txt", "x1.txt", "x12.txt");
		ZipUtilities.removeFilesFromZipFile(zipFile, null, "*.log", "dir\\remove.*", "a+b.txt", "x?.txt");
		assertEquals(List.of("keep.txt", "dir/keep.csv", "x12.txt"), ZipUtilities.getZipFileEntries(zipFile));
		assertEquals(2, tempDirectory.list().length);
	}

	@Test
	public void testRemoveFilesFromZipFileErrors() throws Exception {
		final File zipFile = createZip("remove.zip", "keep.txt");
		assertThrows(IOException.class, () -> ZipUtilities.removeFilesFromZipFile(new File(tempDirectory, "missing.zip"), null, "*"));
		assertThrows(IllegalArgumentException.class, () -> ZipUtilities.removeFilesFromZipFile(zipFile, null, (String[]) null));
		assertThrows(IllegalArgumentException.class, () -> ZipUtilities.removeFilesFromZipFile(zipFile, null, "a", null));
		assertThrows(IOException.class, () -> ZipUtilities.removeFilesFromZipFile(zipFile, createZip("other.zip", "x"), null, "*"));

		// An invalid source zip file leaves no destination file
		final File invalidZipFile = new File(tempDirectory, "invalid.zip");
		Files.write(invalidZipFile.toPath(), DATA_A);
		final File shrinkedZipFile = new File(tempDirectory, "shrinked.zip");
		assertThrows(IOException.class, () -> ZipUtilities.removeFilesFromZipFile(invalidZipFile, shrinkedZipFile, null, "*"));
		assertFalse(shrinkedZipFile.exists());
		assertThrows(IOException.class, () -> ZipUtilities.removeFilesFromZipFile(invalidZipFile, null, "*"));
		assertArrayEquals(DATA_A, Files.readAllBytes(invalidZipFile.toPath()));
	}

	// Unzipping

	@Test
	public void testUnzipErrors() throws Exception {
		final File zipFile = createZip("test.zip", "a.txt");
		final File destination = new File(tempDirectory, "destination");
		destination.mkdir();
		final File someFile = new File(tempDirectory, "file.txt");
		Files.write(someFile.toPath(), DATA_A);

		assertThrows(IOException.class, () -> ZipUtilities.unzipFile(new File(tempDirectory, "missing.zip"), destination));
		assertThrows(IOException.class, () -> ZipUtilities.unzipFile(sourceDirectory, destination));
		assertThrows(IOException.class, () -> ZipUtilities.unzipFile(zipFile, new File(tempDirectory, "missing")));
		assertThrows(IOException.class, () -> ZipUtilities.unzipFile(zipFile, someFile));
	}

	@Test
	public void testUnzipRejectsPathTraversal() throws Exception {
		final File destination = new File(tempDirectory, "destination");
		destination.mkdir();
		for (final String entryName : new String[] { "../evil.txt", "dir/../../evil.txt", "../evil/" }) {
			final File zipFile = createZip("traversal.zip", entryName);
			assertTrue(assertThrows(IOException.class, () -> ZipUtilities.unzipFile(zipFile, destination)).getMessage().contains("outside of the destination directory"), entryName);
			zipFile.delete();
		}
		assertFalse(new File(tempDirectory, "evil.txt").exists());
		assertFalse(new File(tempDirectory, "evil").exists());
	}

	@Test
	public void testUnzipDirectoryEntries() throws Exception {
		// "./" denotes the destination itself, nested files create missing parent directories
		final File zipFile = createZip("dirs.zip", "./", "dir/", "deep/nested/file.txt");
		final File destination = new File(tempDirectory, "destination");
		destination.mkdir();
		ZipUtilities.unzipFile(zipFile, destination);
		assertEquals(Set.of("deep/", "deep/nested/", "deep/nested/file.txt", "dir/"), listRecursively(destination));
	}

	@Test
	public void testUnzipOntoExistingFiles() throws Exception {
		final File destination = new File(tempDirectory, "destination");
		destination.mkdir();
		Files.write(new File(destination, "dir").toPath(), DATA_A);

		// A directory entry for an existing file
		assertTrue(assertThrows(IOException.class, () -> ZipUtilities.unzipFile(createZip("dir.zip", "dir/"), destination)).getMessage().contains("not a directory"));
		// A file entry within an existing file
		assertTrue(assertThrows(IOException.class, () -> ZipUtilities.unzipFile(createZip("file.zip", "dir/file.txt"), destination)).getMessage().contains("not a directory"));
	}

	@Test
	public void testUnzipWithFileInTheWayOfDirectories() throws Exception {
		final File destination = new File(tempDirectory, "destination");
		destination.mkdir();
		Files.write(new File(destination, "file").toPath(), DATA_A);

		// Missing directories below an existing file cannot be created
		assertTrue(assertThrows(IOException.class, () -> ZipUtilities.unzipFile(createZip("dir.zip", "file/sub/"), destination)).getMessage().contains("Cannot create directory"));
		assertTrue(assertThrows(IOException.class, () -> ZipUtilities.unzipFile(createZip("nested.zip", "file/sub/a.txt"), destination)).getMessage().contains("Cannot create directory"));
	}

	@Test
	public void testIsZipArchiveFileOfOtherZipSignature() throws Exception {
		// End of central directory signature of an empty zip file
		final File emptyZipFile = new File(tempDirectory, "empty.zip");
		Files.write(emptyZipFile.toPath(), new byte[] { 0x50, 0x4B, 0x05, 0x06 });
		assertFalse(ZipUtilities.isZipArchiveFile(emptyZipFile));
		final File otherFile = new File(tempDirectory, "other.bin");
		Files.write(otherFile.toPath(), new byte[] { 0x50, 0x00, 0x03, 0x04 });
		assertFalse(ZipUtilities.isZipArchiveFile(otherFile));
	}

	@Test
	public void testUnzipSingleFile() throws Exception {
		final File zipFile = createZip("test.zip", "dir/", "dir/a.txt", "b.txt");
		final File destinationFile = new File(tempDirectory, "extracted.txt");
		assertEquals(destinationFile, ZipUtilities.unzipFile(zipFile, destinationFile, null, "dir/a.txt"));
		assertArrayEquals("dir/a.txt".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(destinationFile.toPath()));

		final File otherDestinationFile = new File(tempDirectory, "other.txt");
		ZipUtilities.unzipFile(zipFile, otherDestinationFile, StandardCharsets.UTF_8, "b.txt");
		assertArrayEquals("b.txt".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(otherDestinationFile.toPath()));
	}

	@Test
	public void testUnzipSingleFileErrors() throws Exception {
		final File zipFile = createZip("test.zip", "dir/", "a.txt");
		final File existingFile = new File(tempDirectory, "existing.txt");
		Files.write(existingFile.toPath(), DATA_A);

		assertThrows(IOException.class, () -> ZipUtilities.unzipFile(new File(tempDirectory, "missing.zip"), new File(tempDirectory, "x"), null, "a.txt"));
		assertThrows(IOException.class, () -> ZipUtilities.unzipFile(sourceDirectory, new File(tempDirectory, "x"), null, "a.txt"));
		assertThrows(IOException.class, () -> ZipUtilities.unzipFile(zipFile, existingFile, null, "a.txt"));
		assertThrows(IOException.class, () -> ZipUtilities.unzipFile(zipFile, new File(tempDirectory, "missing/x.txt"), null, "a.txt"));
		assertThrows(IOException.class, () -> ZipUtilities.unzipFile(zipFile, new File(existingFile, "x.txt"), null, "a.txt"));
		// Directories are no files to extract
		assertTrue(assertThrows(Exception.class, () -> ZipUtilities.unzipFile(zipFile, new File(tempDirectory, "x"), null, "dir/")).getMessage().contains("File not found"));
		assertTrue(assertThrows(Exception.class, () -> ZipUtilities.unzipFile(zipFile, new File(tempDirectory, "x"), null, "missing.txt")).getMessage().contains("File not found"));
	}

	@Test
	public void testNewFile() throws Exception {
		assertEquals(new File(tempDirectory, "dir/a.txt"), ZipUtilities.newFile(tempDirectory, new ZipEntry("dir/a.txt")));
		assertThrows(IOException.class, () -> ZipUtilities.newFile(tempDirectory, new ZipEntry("../a.txt")));
		// The directory itself is allowed for directory entries only
		assertEquals(new File(tempDirectory, "./"), ZipUtilities.newFile(tempDirectory, new ZipEntry("./")));
		assertThrows(IOException.class, () -> ZipUtilities.newFile(tempDirectory, new ZipEntry(".")));
	}

	@Test
	public void testIsInsideDirectory() {
		final String separator = File.separator;
		final String directory = separator + "base";
		assertTrue(ZipUtilities.isInsideDirectory(directory, directory + separator + "file", false));
		assertFalse(ZipUtilities.isInsideDirectory(directory, directory + "2" + separator + "file", false));
		assertFalse(ZipUtilities.isInsideDirectory(directory, directory, false));
		assertTrue(ZipUtilities.isInsideDirectory(directory, directory, true));
		// A directory path with trailing separator, like a root directory
		assertTrue(ZipUtilities.isInsideDirectory(directory + separator, directory + separator + "file", false));
	}

	// Reading zip files

	@Test
	public void testOpenZipFile() throws Exception {
		final File singleFileZip = createZip("single.zip", "dir/", "dir/a.txt");
		try (InputStream inputStream = ZipUtilities.openZipFile(singleFileZip.getAbsolutePath())) {
			assertArrayEquals("dir/a.txt".getBytes(StandardCharsets.UTF_8), inputStream.readAllBytes());
		}

		final File multiFileZip = createZip("multi.zip", "a.txt", "b.txt");
		try (InputStream inputStream = ZipUtilities.openZipFile(multiFileZip.getAbsolutePath(), "b.txt")) {
			assertArrayEquals("b.txt".getBytes(StandardCharsets.UTF_8), inputStream.readAllBytes());
		}
		assertTrue(assertThrows(Exception.class, () -> ZipUtilities.openZipFile(multiFileZip.getAbsolutePath())).getMessage().contains("more than one file"));
		assertTrue(assertThrows(Exception.class, () -> ZipUtilities.openZipFile(multiFileZip.getAbsolutePath(), "c.txt")).getMessage().contains("does not include"));
	}

	@Test
	public void testOpenZipFileErrors() throws Exception {
		assertThrows(IOException.class, () -> ZipUtilities.openZipFile(new File(tempDirectory, "missing.zip").getAbsolutePath()));
		assertThrows(IOException.class, () -> ZipUtilities.openZipFile(sourceDirectory.getAbsolutePath()));
		assertTrue(assertThrows(Exception.class, () -> ZipUtilities.openZipFile(createZip("dirs.zip", "dir/").getAbsolutePath())).getMessage().contains("contains no file"));

		final File invalidZipFile = new File(tempDirectory, "invalid.zip");
		Files.write(invalidZipFile.toPath(), DATA_A);
		final IOException exception = assertThrows(IOException.class, () -> ZipUtilities.openZipFile(invalidZipFile.getAbsolutePath()));
		// The failure of the UTF-8 attempt is kept
		assertEquals(1, exception.getSuppressed().length);
	}

	@Test
	public void testReadExistingZipFile() throws Exception {
		final Map<String, byte[]> content = ZipUtilities.readExistingZipFile(createZip("read.zip", "a.txt", "dir/", "dir/b.txt"));
		assertEquals(List.of("a.txt", "dir/", "dir/b.txt"), List.copyOf(content.keySet()));
		assertArrayEquals(new byte[0], content.get("dir/"));
		assertArrayEquals("dir/b.txt".getBytes(StandardCharsets.UTF_8), content.get("dir/b.txt"));
		assertEquals("a.txt".length() + "dir/b.txt".length(), ZipUtilities.getDataSizeUncompressed(createZip("size.zip", "a.txt", "dir/", "dir/b.txt")));
	}
}
