# Zip

Small, focused Java library for working with **zip** and **tar.gz** archives: create, extend, inspect and
extract archives, read single entries as streams and handle password protected zip files, all with plain
static helper methods and safe defaults.

Package: `de.soderer.zip`

## Features

- **Zip files** (`ZipUtilities`, plain JDK)
  - Zip a single file or a whole directory tree
  - Extract a complete archive or a single entry
  - Add files and directories to an existing archive, or remove entries by wildcard pattern
  - List entries, sum up the uncompressed size, read all entries into memory
  - Open one entry as an `InputStream` (closing the stream also closes the archive)
  - Detect zip files by their signature
  - Configurable charset for entry names (UTF-8 for writing, Cp437 or the UTF-8 flag for reading)
- **Password protected zip files** (`Zip4jUtilities`, based on zip4j)
  - Convert a zip file into a password protected one (AES-256 or legacy ZipCrypto)
  - List entries, determine the uncompressed size and open entries of protected archives
- **tar.gz archives** (`TarGzUtilities`, based on Apache Commons Compress)
  - Extract complete archives including directories, Unix permissions, hard links and symbolic links
  - Create an archive from a single file (POSIX/pax format for long paths and files larger than 8 GiB)
  - Count files, determine the uncompressed size and open single files as `InputStream`

## Safe by default

- **Path traversal protection:** entries pointing outside of the destination directory ("zip slip",
  absolute paths, `..` segments) are rejected when extracting zip and tar.gz archives. Symbolic links in
  tar.gz archives must resolve to a path inside the destination directory.
- **No partial results:** a failed single entry extraction leaves no partial file behind, a failed
  tar.gz extraction removes the destination directory again, and extending or shrinking an existing zip
  file leaves the original unchanged if anything goes wrong.
- **No silent overwriting of archives:** new zip and tar.gz archives, single extracted entries and tar.gz
  destination directories must not exist yet. Only `ZipUtilities.unzipFile(zipFile, destinationDirectory)`
  extracts into an existing directory and replaces files with the same name there.
- **Passwords as `char[]`**, so callers can wipe them after use.

## Requirements

- Java 17 or newer
- Third-party libraries, only needed for the classes that use them:

| Class            | Additional dependency                                                        |
|------------------|------------------------------------------------------------------------------|
| `ZipUtilities`   | none (JDK only)                                                              |
| `Zip4jUtilities` | [zip4j](https://github.com/srikanth-lingala/zip4j) (`net.lingala.zip4j`)      |
| `TarGzUtilities` | [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) |

If you only use `ZipUtilities`, the other jars are not needed at runtime.

## Usage

### Create a zip file

```java
// Creates "/data/reports.zip" next to the source directory
File zipFile = ZipUtilities.zipFile(new File("/data/reports"));

// Or with an explicit destination (must not exist yet) and entry name charset (null for UTF-8)
ZipUtilities.zipFile(new File("/data/reports"), new File("/backup/reports.zip"), null);
```

### Extract a zip file

```java
// Extract everything into an existing directory
ZipUtilities.unzipFile(new File("/backup/reports.zip"), new File("/tmp/restore"));

// Extract a single entry into a new file (charset null: Cp437, or UTF-8 for entries flagged as UTF-8)
File report = ZipUtilities.unzipFile(new File("/backup/reports.zip"), new File("/tmp/january.csv"), null, "reports/january.csv");
```

### Read an entry without extracting

```java
// The stream closes the zip file as well
try (InputStream inputStream = ZipUtilities.openZipFile("/backup/reports.zip", "reports/january.csv")) {
	// read the data
}

// For archives containing exactly one file the entry name can be omitted
try (InputStream inputStream = ZipUtilities.openZipFile("/backup/export.zip")) {
	// read the data
}
```

### Inspect and modify a zip file

```java
List<String> entryNames = ZipUtilities.getZipFileEntries(zipFile);
long uncompressedSize = ZipUtilities.getDataSizeUncompressed(zipFile); // -1 if unknown
Map<String, byte[]> allEntries = ZipUtilities.readExistingZipFile(zipFile);

// Add a file or directory, existing entries are preserved
ZipUtilities.addFileToExistingZipFile(new File("/data/february.csv"), zipFile);

// Remove entries by pattern ("*" any characters, "?" one character)
ZipUtilities.removeFilesFromZipFile(zipFile, null, "*.tmp", "reports/old_?.csv");
```

### Password protected zip files

```java
char[] password = "secret".toCharArray();

// Replace a zip file containing exactly one file by an AES-256 protected one
// (true would use the weak but widely supported ZipCrypto encryption)
Zip4jUtilities.createPasswordSecuredZipFile("/backup/export.zip", password, false);

List<String> entryNames = Zip4jUtilities.getZipFileEntries(new File("/backup/export.zip"), password);

try (InputStream inputStream = Zip4jUtilities.openPasswordSecuredZipFile("/backup/export.zip", password)) {
	// read the data
}

Arrays.fill(password, '\0');
```

### tar.gz archives

```java
// Extract into a new directory (must not exist yet)
TarGzUtilities.decompress(new File("/downloads/app.tar.gz"), new File("/opt/app"));

// Create an archive from a single file, optionally with a different path inside the archive
TarGzUtilities.compress(new File("/backup/db.tar.gz"), new File("/data/db.sql"), "dump/db.sql");

int fileCount = TarGzUtilities.getFilesCount(new File("/downloads/app.tar.gz"));
long size = TarGzUtilities.getUncompressedSize(new File("/downloads/app.tar.gz"));

try (InputStream inputStream = TarGzUtilities.openCompressedFile(new File("/backup/db.tar.gz"), "dump/db.sql")) {
	// read the data
}
```

## Notes

- **tar.gz extraction details:** directories (including empty ones) are created, Unix file permissions are
  applied where the file system supports them, hard links are extracted as copies, device files and FIFOs
  are skipped. Where symbolic links cannot be created (e.g. Windows without the required privilege), links
  to files are extracted as copies and links to directories are skipped.
- **Entry name charsets:** zip archives created by older tools often use Cp437 for entry names. Entries
  flagged as UTF-8 are always read as UTF-8, independent of the given charset.
- **ZipCrypto** is only meant for compatibility with old tools. Prefer AES-256 for anything that needs
  real protection.
