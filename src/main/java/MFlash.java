import javax.xml.bind.DatatypeConverter;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementRef;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;

import static java.lang.String.format;

/**
 * A program to automate flashing Motorola Moto G (2013) stock firmware.
 * <p>
 * Parses the specified XML "flashing" document in flashfile.xml or servicefile.xml using JAXB.
 * <p>
 * By default, when a "step" has "file" and "MD5" attributes the actual MD5 value is calculated from the file and
 * checked against the provided MD5 value, but this can be disabled for speed.
 * <p>
 * JAXB API is used. If you need to build for or run on Java 9+,
 * you will need to uncomment the commented "dependencies" in the pom.xml file.
 */
public class MFlash {
    private static String xmlFilenameString = null;
    private static String firmwareDirString = null;
    private static boolean debug = false;
    private static boolean checkMD5 = true;
    private static boolean test = false;
    private static Path firmwareDirPath;

    public static void main(String[] args) {
        System.out.println("args: " + Arrays.toString(args));
        try {
            for (int i = 0, n = args.length; i < n; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--flash":
                        setXMLFilename("flashfile.xml");
                        break;
                    case "-f":
                    case "--flashing-filename":
                        setXMLFilename(args[++i]);
                        break;
                    case "-t":
                    case "--test":
                        test = true;
                        break;
                    case "-d":
                    case "--debug":
                        debug = true;
                        break;
                    case "--no-md5":
                        checkMD5 = false;
                        break;
                    default:
                        if (null == firmwareDirString)
                            firmwareDirString = arg;
                        else
                            throw new IllegalArgumentException("duplicate FIRMWARE_DIR");
                }
            }

            // Validate xmlFilename
            if (null == xmlFilenameString)
                xmlFilenameString = "servicefile.xml";

            if (null == firmwareDirString)
                firmwareDirString = ".";

            firmwareDirPath = ensureDir(Paths.get(firmwareDirString));
            final Path xmlFilenamePath = ensureFile(firmwareDirPath.resolve(xmlFilenameString));
            final Flashing flashing;
            try (final InputStream in = Files.newInputStream(xmlFilenamePath)) {
                flashing = (Flashing) JAXBContext
                        .newInstance(Flashing.class)
                        .createUnmarshaller()
                        .unmarshal(in);
            }
            if (debug)
                System.out.println(flashing); // Dump read XML as json
            //
            for (Step step : flashing.steps.list) {
                step.run();
            }
        } catch (Exception e) {
            System.err.println();
            e.printStackTrace();
            System.err.println();
            System.err.println("syntax: java MFlash.java [-t|--test] [-d|--debug] [--no-md5] [--flash|--service|-ff FLASHING_FILENAME|--flashing-filename FLASHING_FILENAME] FIRMWARE_DIR");
            System.err.println();
            System.err.println(" -fd or --firmware-dir:      specifies the firmware directory, which contains the image files and the flashing xml script files.");
            System.err.println(" --flash:                    specifies \"flashfile.xml\" as the flashing xml script filename to use.");
            System.err.println(" -ff or --flashing-filename: specifies the flashing xml script filename to use.");
            System.err.println(" -d or --debug:              print the parsed flashing XML as a single line of JSON.");
            System.err.println(" -t or --test:               disables running the generated mfastboot command line.");
            System.err.println(" --no-md5:                   disables MD5 validation of \"file\" with \"MD5\", for each \"step\" element providing these values.");
            System.err.println();
            System.err.println("* flashing xml script filename defaults to \"servicefile.xml\" because that seems to be the most useful.");
        }
        System.out.println("Success!");
    }

    private static void setXMLFilename(String filename) {
        if (null == xmlFilenameString)
            xmlFilenameString = filename;
        else
            throw new IllegalArgumentException("duplicate XML_FILENAME");
    }

    static Path ensureDir(Path dir) throws IOException {
        dir = dir.toRealPath();
        if (!Files.exists(dir))
            throw new FileNotFoundException(dir.toString());
        if (!Files.isDirectory(dir))
            throw new NotDirectoryException(dir.toString());
        return dir;
    }

    static Path ensureFile(Path file) throws IOException {
        if (!Files.exists(file))
            throw new FileNotFoundException(file.toString());
        if (!Files.isRegularFile(file))
            throw new IOException(format("\"%s\" is not a file", file));
        if (!Files.isReadable(file))
            throw new IOException(format("\"%s\" is not a readable file", file));
        return file;
    }

    static void shell(final ProcessBuilder pb) throws IOException, InterruptedException {
        final StringJoiner sj = new StringJoiner("\" \"", "\"", "\"");
        pb.command().forEach(sj::add);
        System.out.println("..Shell: " + sj);
        if (test)
            return;
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.directory(firmwareDirPath.toFile());
        final Process p;
        p = pb.start();
        final int exitValue = p.waitFor();
        if (exitValue == 0)
            System.out.printf("..OK%n%n");
        else
            throw new RuntimeException("exitValue:" + exitValue);
    }

    private static class ToJSON {
        static ToJSON of() {
            return new ToJSON();
        }

        private final StringJoiner sj = new StringJoiner(", ", "{", "}");

        ToJSON add(String key, Object value) {
            if (null != value) {
                if (value instanceof String)
                    sj.add(format("\"%s\": \"%s\"", key, value));
                else
                    sj.add(format("\"%s\": %s", key, value));
            }
            return this;
        }

        @Override
        public String toString() {
            return sj.toString();
        }
    }

    @XmlRootElement(name = "phone_model")
    private static class PhoneModel {
        @XmlAttribute
        public String model;

        @Override
        public String toString() {
            return ToJSON.of()
                         .add("model", model)
                         .toString();
        }
    }

    @XmlRootElement(name = "software_version")
    private static class SoftwareVersion {
        @XmlAttribute
        public String version;

        @Override
        public String toString() {
            return ToJSON.of()
                         .add("version", version)
                         .toString();
        }
    }

    @XmlRootElement(name = "sparsing")
    private static class Sparsing {
        @XmlAttribute
        public boolean enabled;
        @XmlAttribute(name = "max-sparse-size")
        public long maxSparseSize;

        @Override
        public String toString() {
            return ToJSON.of()
                         .add("enabled", enabled)
                         .add("maxSparseSize", maxSparseSize)
                         .toString();
        }
    }

    @XmlRootElement(name = "step")
    private static class Step {

        @XmlAttribute(name = "MD5")
        public String md5;
        @XmlAttribute
        public String operation;
        @XmlAttribute
        public String partition;
        @XmlAttribute
        public String filename;
        @XmlAttribute
        public String var;

        @Override
        public String toString() {
            return "{\"step\": " + ToJSON.of()
                                         .add("md5", md5)
                                         .add("operation", operation)
                                         .add("partition", partition)
                                         .add("filename", filename)
                                         .add("var", var)
                                         .toString() + "}";
        }

        public void run() throws NoSuchAlgorithmException, IOException, InterruptedException {
            System.out.printf("%nRunning %s%n", this);
            // Run command mfastboot command.
            final List<String> command = new ArrayList<>();
            command.add("mfastboot");
            command.add(operation);
            if (null != partition) {
                command.add(partition);
            }
            if (null != filename) {
                final Path file = ensureFile(firmwareDirPath.resolve(filename));
                if (md5 != null && checkMD5) {
                    System.out.println("..checking MD5");
                    final String actualMD5;
                    try (InputStream in = Files.newInputStream(file)) {
                        MessageDigest md = MessageDigest.getInstance("MD5");
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) != -1) {
                            md.update(buf, 0, r);
                        }
                        actualMD5 = DatatypeConverter.printHexBinary(md.digest());
                        if (md5.equals(actualMD5))
                            throw new IOException(format("Expected MD5 of \"%s\" not \"%s\" for file \"%s\"",
                                                         md5, actualMD5, filename));
                    }
                }
                command.add(file.toString());
            }
            if (null != var)
                command.add(var);
            shell(new ProcessBuilder(command));
        }
    }

    @XmlRootElement(name = "steps")
    private static class Steps {
        @XmlAttribute(name = "interface")
        public String interfaceName;
        @XmlElementRef
        public List<Step> list;

        @Override
        public String toString() {
            return ToJSON.of()
                         .add("interface", interfaceName)
                         .add("list", list)
                         .toString();
        }
    }

    @XmlRootElement(name = "interface")
    private static class Interface {
        @XmlAttribute
        public String name;

        @Override
        public String toString() {
            return "{\"interface\": " + ToJSON.of()
                                              .add("name", name)
                                              .toString() + "}";
        }
    }

    @XmlRootElement(name = "interfaces")
    private static class Interfaces {
        @XmlElementRef
        public List<Interface> list;

        @Override
        public String toString() {
            return list.toString();
        }
    }

    @XmlRootElement(name = "header")
    private static class Header {
        @XmlElement(name = "phone_model")
        public PhoneModel phoneModel;
        @XmlElement(name = "software_version")
        public SoftwareVersion softwareVersion;
        @XmlElement
        public Sparsing sparsing;
        @XmlElement
        public Interfaces interfaces;

        @Override
        public String toString() {
            return ToJSON.of()
                         .add("phone_model", phoneModel)
                         .add("software_version", softwareVersion)
                         .add("sparsing", sparsing)
                         .add("interfaces", interfaces)
                         .toString();
        }
    }


    @XmlRootElement(name = "flashing")
    private static class Flashing {
        @XmlElement
        public Header header;
        @XmlElement
        public Steps steps;

        @Override
        public String toString() {
            return "{\"flashing\": " + ToJSON.of()
                                             .add("header", header)
                                             .add("steps", steps)
                                             .toString() + "}";
        }
    }
}
