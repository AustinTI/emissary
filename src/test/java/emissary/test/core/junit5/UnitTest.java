package emissary.test.core.junit5;

import emissary.command.ServerCommand;
import emissary.config.ConfigUtil;
import emissary.core.EmissaryException;
import emissary.util.ThreadDump;
import emissary.util.io.ResourceReader;

import org.apache.commons.io.FilenameUtils;
import org.jdom2.Document;
import org.jdom2.input.SAXBuilder;
import org.jdom2.input.sax.XMLReaders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.Arguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nullable;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Base class of all the unit tests
 */
@ExtendWith(UnitTest.DumpFailuresWatcher.class)
public abstract class UnitTest {

    // Runtime typed logger
    protected Logger logger = LoggerFactory.getLogger(this.getClass());

    protected static List<String> answerFiles = new ArrayList<>();

    @TempDir
    public static File temporaryDirectory;
    protected static String TMPDIR = "/tmp";
    @Nullable
    protected Package thisPackage = null;
    @Nullable
    protected String origConfigPkg = null;

    /**
     * Create a UnitTest
     */
    public UnitTest() {
        configure();
    }

    /**
     * Create a named unit test
     */
    public UnitTest(String name) {
        configure();
    }

    @BeforeAll
    public static void setupTmpDirJunit5() {
        TMPDIR = temporaryDirectory.getAbsolutePath();
    }

    @BeforeEach
    public void setUp() throws Exception {
        // placeholder
    }

    @AfterEach
    public void tearDown() throws Exception {
        restoreConfig();
        assertMaxNonSystemThreadCount(1);
    }

    @AfterAll
    public static void clearAnswerFiles() {
        answerFiles.clear();
    }

    /**
     * Configure the test stuff
     * <p>
     * Beware though, if you use @BeforeClass this will not have had a chance to run. So you can do something like
     * UnitTest.setupSystemProperties(); in the @BeforeClass. See FlexibleDateTimeParserTest for an example
     */
    protected void configure() {
        thisPackage = this.getClass().getPackage();
        setupSystemProperties();
    }

    public static synchronized void setupSystemProperties() {
        // mostly just to get the system properties set
        // synchronized since multiple threads are testing at the same time
        String projectBase = System.getenv("PROJECT_BASE");
        if (projectBase == null) {
            fail("PROJECT_BASE is not set");
        }
        // setup the environment stuff
        try {
            ServerCommand.parse(ServerCommand.class, "-m", "cluster").setupCommand();
        } catch (EmissaryException e) {
            fail("Unable to setup Emissary environment", e);
        }
    }

    public void assertMaxNonSystemThreadCount(int max) {
        ThreadDump td = new ThreadDump();
        ThreadInfo[] ti = td.getThreadInfo(true);
        if (ti.length > max) {
            StringBuilder sb = new StringBuilder();
            for (ThreadInfo t : ti) {
                sb.append(t.getThreadName()).append(" ");
            }
            assertTrue(max <= ti.length, "Not expecting " + ti.length + " threads from " + this.getClass().getName() + ": " + sb);
        }
    }

    /**
     * Get all test resources (*.dat) for this class
     */
    protected List<String> getMyTestResources() {
        ResourceReader rr = new ResourceReader();
        return rr.findDataResourcesFor(this.getClass());
    }

    /**
     * Get all test resources (*.dat) for this class in a format suitable for Junit Parameterized Tests
     */
    public static Stream<? extends Arguments> getMyTestParameterFiles(Class<?> clz) {
        return getMyTestParameterFiles(clz, clz);
    }

    /**
     * Get test resources (*.dat) and test answers when they are in two different directories.
     *
     * @param dataClz class that provides the test resource (*.dat) files
     * @param ansClz class that provides the test answer files
     * @return the stream of test resource files to be used for JUnit Parameterized Tests
     */
    public static Stream<? extends Arguments> getMyTestParameterFiles(Class<?> dataClz, Class<?> ansClz) {
        ResourceReader rr = new ResourceReader();
        List<String> rs = rr.findDataResourcesFor(dataClz);
        answerFiles = getMyTestAnswerFiles(rr, ansClz);
        return rs.stream().map(Arguments::of);
    }

    private static List<String> getMyTestAnswerFiles(ResourceReader resourceReader, Class<?> ansClz) {
        return resourceReader.findXmlResourcesFor(ansClz);
    }

    /**
     * Get all xml resources (*.xml) for this class
     */
    protected List<String> getMyXmlResources() {
        ResourceReader rr = new ResourceReader();
        return rr.findXmlResourcesFor(this.getClass());
    }

    /**
     * Pause for the specified number of millis without throwing any exceptions when it doesn't work
     *
     * @param millis how long to pause
     */
    protected void pause(long millis) {
        Thread.yield();
        try {
            Thread.sleep(millis);
        } catch (Exception ex) {
            // empty exception block
            logger.debug("Thread interrupted", ex);
        }
    }

    /**
     * Set up configuration
     *
     * @param configPath The path to use for config.dir, or null if the value should not be changed.
     * @param pkg use this.pkg for config.pkg
     */
    protected void setConfig(final String configPath, boolean pkg) throws EmissaryException {
        // TODO: refactor this. Changing the pkg affected toResourceName, which could have the
        // same negative affect as change the config dir property. Then get rid of this
        // origConfigDir = System.getProperty(ConfigUtil.CONFIG_DIR_PROPERTY);
        origConfigPkg = System.getProperty(ConfigUtil.CONFIG_PKG_PROPERTY);
        if (configPath != null) {
            throw new UnsupportedOperationException("We no longer use a tmp directory, fix this");
            // System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, configPath);
        }
        if (pkg) {
            System.setProperty(ConfigUtil.CONFIG_PKG_PROPERTY, thisPackage.getName());
        }
        ConfigUtil.initialize();
    }

    /**
     * Restore config pkg to original values
     */
    protected void restoreConfig() throws EmissaryException {
        if (origConfigPkg != null) {
            System.setProperty(ConfigUtil.CONFIG_PKG_PROPERTY, origConfigPkg);
            origConfigPkg = null;
        }

        ConfigUtil.initialize();
    }

    /**
     * Get an JDOM XML document corresponding to a test resource
     */
    @Nullable
    protected Document getAnswerDocumentFor(String resource) {
        int datPos = resource.lastIndexOf(ResourceReader.DATA_SUFFIX);
        if (datPos == -1) {
            logger.debug("Resource is not a DATA file {}", resource);
            return null;
        }

        String aname = "";
        if (answerFiles.isEmpty()) {
            aname = resource.substring(0, datPos) + ResourceReader.XML_SUFFIX;
        } else {
            // if answer files are in different directory than data files, this will be used to find matching answer to data pair
            String testFileName = FilenameUtils.getBaseName(resource);
            for (String answer : answerFiles) {
                if (FilenameUtils.getBaseName(answer).equals(testFileName)) {
                    aname = answer;
                    break;
                }
            }
        }

        SAXBuilder builder = new SAXBuilder(XMLReaders.NONVALIDATING);
        Document answerDoc;
        try (InputStream is = new ResourceReader().getResourceAsStream(aname)) {
            answerDoc = builder.build(is);
        } catch (Exception ex) {
            logger.debug("No answer document provided for {}", aname, ex);
            return null;
        }
        return answerDoc;
    }

    public static class DumpFailuresWatcher implements TestWatcher {
        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            LoggerFactory.getLogger(context.getRequiredTestClass().getName())
                    .error("{} failed {}", context.getDisplayName(), cause.getMessage());
        }
    }
}
