package fr.pilato.elasticsearch.crawler.fs.client;

import com.carrotsearch.randomizedtesting.RandomizedTest;
import fr.pilato.elasticsearch.crawler.fs.beans.Doc;
import fr.pilato.elasticsearch.crawler.fs.framework.TimeValue;
import fr.pilato.elasticsearch.crawler.fs.framework.bulk.FsCrawlerBulkResponse;
import fr.pilato.elasticsearch.crawler.fs.settings.FsSettings;
import fr.pilato.elasticsearch.crawler.fs.settings.FsSettingsLoader;
import fr.pilato.elasticsearch.crawler.fs.settings.ServerUrl;
import fr.pilato.elasticsearch.crawler.fs.test.framework.AbstractFSCrawlerTestCase;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hamcrest.Matcher;
import org.junit.*;
import org.testcontainers.containers.NginxContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.MountableFile;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static fr.pilato.elasticsearch.crawler.fs.client.ElasticsearchClient.CHECK_NODES_EVERY;
import static fr.pilato.elasticsearch.crawler.fs.framework.Await.awaitBusy;
import static fr.pilato.elasticsearch.crawler.fs.framework.FsCrawlerUtil.INDEX_SUFFIX_FOLDER;
import static fr.pilato.elasticsearch.crawler.fs.framework.FsCrawlerUtil.readPropertiesFromClassLoader;
import static fr.pilato.elasticsearch.crawler.fs.settings.ServerUrl.decodeCloudId;
import static org.apache.commons.lang3.StringUtils.split;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

public class ElasticsearchClientIT extends AbstractFSCrawlerTestCase {
    private static final Logger logger = LogManager.getLogger();
    private final static String DEFAULT_TEST_CLUSTER_URL = "https://127.0.0.1:9200";
    private final static String DEFAULT_USERNAME = "elastic";
    private final static String DEFAULT_PASSWORD = "changeme";
    private static final String DOC_INDEX_NAME = "fscrawler_elasticsearch_client_i_t";
    private static final String FOLDER_INDEX_NAME = DOC_INDEX_NAME + "_folder";
    private static String testClusterUrl = null;
    private static final TestContainerHelper testContainerHelper = new TestContainerHelper();
    private final static boolean testCheckCertificate = getSystemProperty("tests.cluster.check_ssl", true);
    private static String testCaCertificate;
    private static IElasticsearchClient esClient;
    @Deprecated
    protected static final String testClusterUser = getSystemProperty("tests.cluster.user", DEFAULT_USERNAME);
    @Deprecated
    protected static final String testClusterPass = getSystemProperty("tests.cluster.pass", DEFAULT_PASSWORD);
    protected static String testApiKey = getSystemProperty("tests.cluster.apiKey", null);

    @BeforeClass
    public static void startServices() throws IOException, ElasticsearchClientException {
        if (testClusterUrl == null) {
            String testClusterCloudId = System.getProperty("tests.cluster.cloud_id");
            if (testClusterCloudId != null && !testClusterCloudId.isEmpty()) {
                testClusterUrl = decodeCloudId(testClusterCloudId);
                logger.debug("Using cloud id [{}] meaning actually [{}]", testClusterCloudId, testClusterUrl);
            } else {
                testClusterUrl = getSystemProperty("tests.cluster.url", DEFAULT_TEST_CLUSTER_URL);
                if (testClusterUrl.isEmpty()) {
                    // When running from Maven CLI, tests.cluster.url is empty and not null...
                    testClusterUrl = DEFAULT_TEST_CLUSTER_URL;
                }
            }
        }

        boolean checkCertificate = testCheckCertificate;
        esClient = startClient(checkCertificate);
        if (esClient == null && checkCertificate) {
            testClusterUrl = testClusterUrl.replace("http:", "https:");
            logger.info("Trying without SSL verification on [{}].", testClusterUrl);
            checkCertificate = false;
            esClient = startClient(checkCertificate);
        }

        if (esClient == null) {
            logger.info("Elasticsearch is not running on [{}]. We start TestContainer.", testClusterUrl);
            testClusterUrl = testContainerHelper.startElasticsearch(true);
            // Write the Ca Certificate on disk if exists (with versions < 8, no self-signed certificate)
            if (testContainerHelper.getCertAsBytes() != null) {
                Path clusterCaCrtPath = rootTmpDir.resolve("cluster-ca.crt");
                Files.write(clusterCaCrtPath, testContainerHelper.getCertAsBytes());
                testCaCertificate = clusterCaCrtPath.toAbsolutePath().toString();
            }
            checkCertificate = testCheckCertificate;
            esClient = startClient(checkCertificate);
        }

        assumeThat("Integration tests are skipped because we have not been able to find an Elasticsearch cluster",
                esClient, notNullValue());

        String version = esClient.getVersion();
        logger.info("Starting integration tests against an external cluster running elasticsearch [{}]", version);
    }

    private static ElasticsearchClient startClient(boolean sslVerification) throws ElasticsearchClientException {
        logger.info("Starting a client against [{}] with [{}] as a CA certificate and ssl check [{}]",
                testClusterUrl, testCaCertificate, sslVerification);
        // We build the elasticsearch Client based on the parameters
        FsSettings fsSettings = FsSettingsLoader.load();
        fsSettings.getElasticsearch().setNodes(Collections.singletonList(new ServerUrl(testClusterUrl)));
        fsSettings.getElasticsearch().setSslVerification(sslVerification);
        fsSettings.getElasticsearch().setCaCertificate(testCaCertificate);
        if (testApiKey != null) {
            fsSettings.getElasticsearch().setApiKey(testApiKey);
        } else if (testClusterUser != null && testClusterPass != null) {
            fsSettings.getElasticsearch().setUsername(testClusterUser);
            fsSettings.getElasticsearch().setPassword(testClusterPass);
        }
        fsSettings.getElasticsearch().setIndex(DOC_INDEX_NAME);
        fsSettings.getElasticsearch().setIndexFolder(FOLDER_INDEX_NAME);

        ElasticsearchClient client = new ElasticsearchClient(null, fsSettings);

        try {
            client.start();
            return client;
        } catch (ElasticsearchClientException e) {
            logger.info("Elasticsearch is not running on [{}]", testClusterUrl);
            if ((e.getCause() instanceof SocketException ||
                    (e.getCause() instanceof ProcessingException && e.getCause().getCause() instanceof SSLException))
                    && testClusterUrl.toLowerCase().startsWith("https")) {
                logger.info("May be we are trying to run against a <8.x cluster. So let's fallback to http.");
                testClusterUrl = testClusterUrl.replace("https", "http");
                return startClient(sslVerification);
            }
        }
        return null;
    }

    @AfterClass
    public static void stopServices() throws IOException {
        logger.info("Stopping integration tests against an external cluster");
        if (esClient != null) {
            esClient.close();
            esClient = null;
            logger.info("Document service stopped");
        }
        testCaCertificate = null;
    }

    @Before
    public void cleanExistingIndex() throws ElasticsearchClientException {
        logger.info(" -> Removing existing index [{}*]", getCrawlerName());
        esClient.deleteIndex(getCrawlerName());
        esClient.deleteIndex(getCrawlerName() + INDEX_SUFFIX_FOLDER);
    }

    @Test
    public void testDeleteIndex() throws ElasticsearchClientException {
        esClient.deleteIndex("does-not-exist-index");
        esClient.createIndex(getCrawlerName(), false, null);
        assertThat(esClient.isExistingIndex(getCrawlerName()), is(true));
        esClient.deleteIndex(getCrawlerName());
        assertThat(esClient.isExistingIndex(getCrawlerName()), is(false));
    }

    @Test
    public void testWaitForHealthyIndex() throws ElasticsearchClientException {
        esClient.createIndex(getCrawlerName(), false, null);
        esClient.waitForHealthyIndex(getCrawlerName());
        try {
            esClient.waitForHealthyIndex("does-not-exist-index");
            fail("We should have raised a ClientErrorException");
        } catch (ClientErrorException e) {
            assertThat(e.getResponse().getStatus(), is(404));
        }
    }

    /**
     * We don't need to create indices anymore with ES >= 7
     * @throws ElasticsearchClientException in case of error
     */
    @Test
    @Deprecated
    public void testCreateIndex() throws ElasticsearchClientException {
        esClient.createIndex(getCrawlerName(), false, null);
        boolean exists = esClient.isExistingIndex(getCrawlerName());
        assertThat(exists, is(true));
    }

    /**
     * We don't need to create indices anymore with ES >= 7
     * @throws ElasticsearchClientException in case of error
     */
    @Test
    @Deprecated
    public void testCreateIndexWithSettings() throws ElasticsearchClientException {
        esClient.createIndex(getCrawlerName(), false, "{\n" +
                "  \"settings\": {\n" +
                "    \"refresh_interval\": \"5s\"\n" +
                "  }\n" +
                "}\n");
        boolean exists = esClient.isExistingIndex(getCrawlerName());
        assertThat(exists, is(true));
    }

    @Test
    public void testRefresh() throws ElasticsearchClientException {
        esClient.createIndex(getCrawlerName(), false, null);
        esClient.refresh(getCrawlerName());
    }

    /**
     * We don't need to create indices anymore with ES >= 7
     * @throws ElasticsearchClientException in case of error
     */
    @Test
    @Deprecated
    public void testCreateIndexAlreadyExistsShouldFail() throws ElasticsearchClientException {
        esClient.createIndex(getCrawlerName(), false, null);
        esClient.waitForHealthyIndex(getCrawlerName());
        try {
            esClient.createIndex(getCrawlerName(), false, null);
            fail("we should reject creation of an already existing index");
        } catch (ElasticsearchClientException e) {
            assertThat(e.getMessage(), containsString("already exists"));
        }
    }

    /**
     * We don't need to create indices anymore with ES >= 7
     * @throws ElasticsearchClientException in case of error
     */
    @Test
    @Deprecated
    public void testCreateIndexAlreadyExistsShouldBeIgnored() throws ElasticsearchClientException {
        esClient.createIndex(getCrawlerName(), false, null);
        esClient.waitForHealthyIndex(getCrawlerName());
        esClient.createIndex(getCrawlerName(), true, null);
    }

    /**
     * We don't need to create indices anymore with ES >= 7
     * @throws ElasticsearchClientException in case of error
     */
    @Test
    @Deprecated
    public void testCreateIndexWithErrors() {
        try {
            esClient.createIndex(getCrawlerName(), false, "{this is wrong}");
            fail("we should reject creation of an already existing index");
        } catch (ElasticsearchClientException e) {
            assertThat(e.getMessage(), containsString("error while creating index"));
        }
    }

    @Test
    public void testSearch() throws Exception {
        esClient.createIndex(getCrawlerName(), false, "{\n" +
                "  \"mappings\": {\n" +
                "    \"properties\": {\n" +
                "      \"foo\": {\n" +
                "        \"properties\": {\n" +
                "          \"bar\": {\n" +
                "            \"type\": \"text\",\n" +
                "            \"store\": true,\n" +
                "            \"fields\": {\n" +
                "              \"raw\": { \n" +
                "                \"type\":  \"keyword\"\n" +
                "              }\n" +
                "            }\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}");

        esClient.indexSingle(getCrawlerName(), "1", "{ \"foo\": { \"bar\": \"bar\" } }", null);
        esClient.indexSingle(getCrawlerName(), "2", "{ \"foo\": { \"bar\": \"baz\" } }", null);
        esClient.indexSingle(getCrawlerName(), "3", "{ \"number\": 1 }", null);
        esClient.indexSingle(getCrawlerName(), "4", "{ \"number\": 2 }", null);

        // Wait until we have 4 documents indexed
        countTestHelper(new ESSearchRequest().withIndex(getCrawlerName()), 4L, TimeValue.timeValueSeconds(10));

        // match_all
        {
            ESSearchResponse response = esClient.search(new ESSearchRequest().withIndex(getCrawlerName()));
            assertThat(response.getTotalHits(), is(4L));

            for (ESSearchHit hit : response.getHits()) {
                assertThat(hit.getIndex(), is(getCrawlerName()));
                assertThat(hit.getId(), isOneOf("1", "2", "3", "4"));
                assertThat(hit.getVersion(), is(1L));
                assertThat(hit.getSource(), not(isEmptyOrNullString()));
                assertThat(hit.getHighlightFields().isEmpty(), is(true));
                assertThat(hit.getStoredFields(), nullValue());
            }
        }

        // term
        {
            ESSearchResponse response = esClient.search(new ESSearchRequest().withIndex(getCrawlerName())
                    .withESQuery(new ESTermQuery("foo.bar", "bar")));
            assertThat(response.getTotalHits(), is(1L));
            assertThat(response.getHits().get(0).getIndex(), is(getCrawlerName()));
            assertThat(response.getHits().get(0).getId(), is("1"));
            assertThat(response.getHits().get(0).getVersion(), is(1L));
            assertThat(response.getHits().get(0).getSource(), not(isEmptyOrNullString()));
            assertThat(response.getHits().get(0).getHighlightFields().isEmpty(), is(true));
            assertThat(response.getHits().get(0).getStoredFields(), nullValue());
        }

        // match
        {
            ESSearchResponse response = esClient.search(new ESSearchRequest().withIndex(getCrawlerName())
                    .withESQuery(new ESMatchQuery("foo.bar", "bar")));
            assertThat(response.getTotalHits(), is(1L));
            assertThat(response.getHits().get(0).getIndex(), is(getCrawlerName()));
            assertThat(response.getHits().get(0).getId(), is("1"));
            assertThat(response.getHits().get(0).getVersion(), is(1L));
            assertThat(response.getHits().get(0).getSource(), not(isEmptyOrNullString()));
            assertThat(response.getHits().get(0).getHighlightFields().isEmpty(), is(true));
            assertThat(response.getHits().get(0).getStoredFields(), nullValue());
        }

        // prefix
        {
            ESSearchResponse response = esClient.search(new ESSearchRequest().withIndex(getCrawlerName())
                    .withESQuery(new ESPrefixQuery("foo.bar", "ba")));
            assertThat(response.getTotalHits(), is(2L));

            for (ESSearchHit hit : response.getHits()) {
                assertThat(hit.getIndex(), is(getCrawlerName()));
                assertThat(hit.getId(), isOneOf("1", "2"));
                assertThat(hit.getVersion(), is(1L));
                assertThat(hit.getSource(), not(isEmptyOrNullString()));
                assertThat(hit.getHighlightFields().isEmpty(), is(true));
                assertThat(hit.getStoredFields(), nullValue());
            }
        }

        // range - lower than 2
        {
            ESSearchResponse response = esClient.search(new ESSearchRequest().withIndex(getCrawlerName())
                    .withESQuery(new ESRangeQuery("number").withLt(2)));
            assertThat(response.getTotalHits(), is(1L));
            assertThat(response.getHits().get(0).getIndex(), is(getCrawlerName()));
            assertThat(response.getHits().get(0).getId(), is("3"));
            assertThat(response.getHits().get(0).getVersion(), is(1L));
            assertThat(response.getHits().get(0).getSource(), not(isEmptyOrNullString()));
            assertThat(response.getHits().get(0).getHighlightFields().isEmpty(), is(true));
            assertThat(response.getHits().get(0).getStoredFields(), nullValue());
        }

        // range - greater or equal to 2
        {
            ESSearchResponse response = esClient.search(new ESSearchRequest().withIndex(getCrawlerName())
                    .withESQuery(new ESRangeQuery("number").withGte(2)));
            assertThat(response.getTotalHits(), is(1L));
            assertThat(response.getHits().get(0).getIndex(), is(getCrawlerName()));
            assertThat(response.getHits().get(0).getId(), is("4"));
            assertThat(response.getHits().get(0).getVersion(), is(1L));
            assertThat(response.getHits().get(0).getSource(), not(isEmptyOrNullString()));
            assertThat(response.getHits().get(0).getHighlightFields().isEmpty(), is(true));
            assertThat(response.getHits().get(0).getStoredFields(), nullValue());
        }

        // bool with prefix and match
        {
            ESSearchResponse response = esClient.search(new ESSearchRequest().withIndex(getCrawlerName())
                    .withESQuery(new ESBoolQuery()
                            .addMust(new ESPrefixQuery("foo.bar", "ba"))
                            .addMust(new ESMatchQuery("foo.bar", "bar"))
                    ));
            assertThat(response.getTotalHits(), is(1L));
            assertThat(response.getHits().get(0).getIndex(), is(getCrawlerName()));
            assertThat(response.getHits().get(0).getId(), is("1"));
            assertThat(response.getHits().get(0).getVersion(), is(1L));
            assertThat(response.getHits().get(0).getSource(), not(isEmptyOrNullString()));
            assertThat(response.getHits().get(0).getHighlightFields().isEmpty(), is(true));
            assertThat(response.getHits().get(0).getStoredFields(), nullValue());
        }

        // Highlighting
        {
            ESSearchResponse response = esClient.search(new ESSearchRequest().withIndex(getCrawlerName())
                    .withESQuery(new ESMatchQuery("foo.bar", "bar"))
                    .addHighlighter("foo.bar")
            );
            assertThat(response.getTotalHits(), is(1L));
            assertThat(response.getHits().get(0).getIndex(), is(getCrawlerName()));
            assertThat(response.getHits().get(0).getId(), is("1"));
            assertThat(response.getHits().get(0).getVersion(), is(1L));
            assertThat(response.getHits().get(0).getSource(), not(isEmptyOrNullString()));
            assertThat(response.getHits().get(0).getHighlightFields().size(), is(1));
            assertThat(response.getHits().get(0).getHighlightFields(), hasKey("foo.bar"));
            assertThat(response.getHits().get(0).getHighlightFields().get("foo.bar"), iterableWithSize(1));
            assertThat(response.getHits().get(0).getHighlightFields().get("foo.bar"), hasItem("<em>bar</em>"));
            assertThat(response.getHits().get(0).getStoredFields(), nullValue());
        }

        // Fields
        {
            ESSearchResponse response = esClient.search(new ESSearchRequest().withIndex(getCrawlerName())
                    .withESQuery(new ESPrefixQuery("foo.bar", "ba"))
                    .addStoredField("foo.bar")
            );
            assertThat(response.getTotalHits(), is(2L));

            for (ESSearchHit hit : response.getHits()) {
                assertThat(hit.getIndex(), is(getCrawlerName()));
                assertThat(hit.getId(), isOneOf("1", "2"));
                assertThat(hit.getVersion(), is(1L));
                assertThat(hit.getSource(), isEmptyOrNullString());
                assertThat(hit.getHighlightFields().isEmpty(), is(true));
                assertThat(hit.getStoredFields(), notNullValue());
                assertThat(hit.getStoredFields(), hasKey(is("foo.bar")));
                assertThat(hit.getStoredFields(), hasEntry(is("foo.bar"), hasItem(isOneOf("bar", "baz"))));
            }
        }

        // Fields with _source
        {
            ESSearchResponse response = esClient.search(new ESSearchRequest().withIndex(getCrawlerName())
                    .withESQuery(new ESPrefixQuery("foo.bar", "ba"))
                    .addStoredField("_source")
                    .addStoredField("foo.bar")
            );
            assertThat(response.getTotalHits(), is(2L));

            for (ESSearchHit hit : response.getHits()) {
                assertThat(hit.getIndex(), is(getCrawlerName()));
                assertThat(hit.getId(), isOneOf("1", "2"));
                assertThat(hit.getVersion(), is(1L));
                assertThat(hit.getSource(), not(isEmptyOrNullString()));
                assertThat(hit.getHighlightFields().isEmpty(), is(true));
                assertThat(hit.getStoredFields(), notNullValue());
                assertThat(hit.getStoredFields(), hasKey(is("foo.bar")));
                assertThat(hit.getStoredFields(), hasEntry(is("foo.bar"), hasItem(isOneOf("bar", "baz"))));
            }
        }

        // Aggregation
        {
            ESSearchResponse response = esClient.search(new ESSearchRequest().withIndex(getCrawlerName())
                    .withAggregation(new ESTermsAggregation("foobar1", "foo.bar.raw"))
                    .withAggregation(new ESTermsAggregation("foobar2", "foo.bar.raw"))
                    .withSize(0)
            );
            assertThat(response.getTotalHits(), is(4L));
            assertThat(response.getAggregations(), notNullValue());
            assertThat(response.getAggregations().size(), is(2));
            assertThat(response.getAggregations(), hasKey("foobar1"));
            assertThat(response.getAggregations().get("foobar1").getName(), is("foobar1"));
            assertThat(response.getAggregations().get("foobar1").getBuckets(), hasSize(2));
            assertThat(response.getAggregations().get("foobar1").getBuckets(), hasItems(
                    new ESTermsAggregation.ESTermsBucket("bar", 1),
                    new ESTermsAggregation.ESTermsBucket("baz", 1)
            ));
            assertThat(response.getAggregations(), hasKey("foobar2"));
            assertThat(response.getAggregations().get("foobar2").getName(), is("foobar2"));
            assertThat(response.getAggregations().get("foobar2").getBuckets(), hasSize(2));
            assertThat(response.getAggregations().get("foobar2").getBuckets(), hasItems(
                    new ESTermsAggregation.ESTermsBucket("bar", 1),
                    new ESTermsAggregation.ESTermsBucket("baz", 1)
            ));
        }
    }

    @Test
    public void testFindVersion() throws ElasticsearchClientException {
        String version = esClient.getVersion();
        logger.info("Current elasticsearch version: [{}]", version);

        // If we did not use an external URL but the docker instance we can test for sure that the version is the expected one
        if (System.getProperty("tests.cluster.url") == null) {
            Properties properties = readPropertiesFromClassLoader("elasticsearch.version.properties");
            assertThat(version, is(properties.getProperty("version")));
        }
    }

    @Test
    public void testPipeline() throws ElasticsearchClientException {
        String crawlerName = getCrawlerName();

        // Create an empty ingest pipeline
        String pipeline = "{\n" +
                "  \"description\": \"Testing Grok on PDF upload\",\n" +
                "  \"processors\": [\n" +
                "    {\n" +
                "      \"gsub\": {\n" +
                "        \"field\": \"content\",\n" +
                "        \"pattern\": \"\\n\",\n" +
                "        \"replacement\": \"-\"\n" +
                "      }\n" +
                "    },\n" +
                "    {\n" +
                "      \"grok\": {\n" +
                "        \"field\": \"content\",\n" +
                "        \"patterns\": [\n" +
                "          \"%{DATA}%{IP:ip_addr} %{GREEDYDATA}\"\n" +
                "        ]\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        esClient.performLowLevelRequest("PUT", "/_ingest/pipeline/" + crawlerName, pipeline);

        assertThat(esClient.isExistingPipeline(crawlerName), is(true));
        assertThat(esClient.isExistingPipeline(crawlerName + "_foo"), is(false));
    }

    @Test
    public void testBulk() throws Exception {
        {
            long nbItems = RandomizedTest.randomLongBetween(5, 20);

            ElasticsearchBulkRequest bulkRequest = new ElasticsearchBulkRequest();

            // Add some index op
            for (int i = 0; i < nbItems; i++) {
                bulkRequest.add(new ElasticsearchIndexOperation(getCrawlerName(),
                        "" + i,
                        null,
                        "{\"foo\":{\"bar\":\"bar\"},\"number\": " + i + "}"));
            }

            ElasticsearchEngine engine = new ElasticsearchEngine(esClient);
            ElasticsearchBulkResponse bulkResponse = engine.bulk(bulkRequest);
            assertThat(bulkResponse.hasFailures(), is(false));
            assertThat(bulkResponse.getItems(), not(emptyIterable()));

            // Wait until we have the expected number of documents indexed
            countTestHelper(new ESSearchRequest().withIndex(getCrawlerName()), nbItems, TimeValue.timeValueSeconds(10));
        }
        {
            esClient.deleteIndex(getCrawlerName());
            long nbItems = RandomizedTest.randomLongBetween(5, 20);
            long nbItemsToDelete = RandomizedTest.randomLongBetween(1, nbItems);

            ElasticsearchBulkRequest bulkRequest = new ElasticsearchBulkRequest();

            // Add some index op
            for (int i = 0; i < nbItems; i++) {
                bulkRequest.add(new ElasticsearchIndexOperation(getCrawlerName(),
                        "" + i,
                        null,
                        "{\"foo\":{\"bar\":\"bar\"},\"number\": " + i + "}"));
            }
            // Add some delete op
            for (int i = 0; i < nbItemsToDelete; i++) {
                bulkRequest.add(new ElasticsearchDeleteOperation(getCrawlerName(), "" + i));
            }

            ElasticsearchEngine engine = new ElasticsearchEngine(esClient);
            ElasticsearchBulkResponse bulkResponse = engine.bulk(bulkRequest);
            assertThat(bulkResponse.hasFailures(), is(false));
            assertThat(bulkResponse.getItems(), not(emptyIterable()));

            // Wait until we have the expected number of documents indexed
            countTestHelper(new ESSearchRequest().withIndex(getCrawlerName()), nbItems - nbItemsToDelete, TimeValue.timeValueSeconds(10));
        }
        {
            esClient.deleteIndex(getCrawlerName());
            long nbItems = RandomizedTest.randomLongBetween(5, 20);

            ElasticsearchBulkRequest bulkRequest = new ElasticsearchBulkRequest();

            // Add some index op
            for (int i = 0; i < nbItems; i++) {
                bulkRequest.add(new ElasticsearchIndexOperation(getCrawlerName(),
                        "" + i,
                        null,
                        "{\n" +
                                "          \"foo\" : {\n" +
                                "            \"bar\" : \"baz\"\n" +
                                "          }\n" +
                                "        }"));
            }

            ElasticsearchEngine engine = new ElasticsearchEngine(esClient);
            ElasticsearchBulkResponse bulkResponse = engine.bulk(bulkRequest);
            assertThat(bulkResponse.hasFailures(), is(false));
            assertThat(bulkResponse.getItems(), not(emptyIterable()));

            // Wait until we have the expected number of documents indexed
            countTestHelper(new ESSearchRequest().withIndex(getCrawlerName()), nbItems, TimeValue.timeValueSeconds(10));
        }
        {
            esClient.deleteIndex(getCrawlerName());
            String indexSettings = "{\n" +
                    "  \"mappings\": {\n" +
                    "    \"properties\": {\n" +
                    "      \"foo\": {\n" +
                    "        \"properties\": {\n" +
                    "          \"number\": {\n" +
                    "            \"type\": \"long\"\n" +
                    "          }\n" +
                    "        }\n" +
                    "      }\n" +
                    "    }\n" +
                    "  }\n" +
                    "}";

            esClient.createIndex(getCrawlerName(), false, indexSettings);

            long nbItems = RandomizedTest.randomLongBetween(6, 20);

            ElasticsearchBulkRequest bulkRequest = new ElasticsearchBulkRequest();

            // Add some index op
            // Every 5 ops, we had a failing document
            long nbErrors = 0;
            for (int i = 0; i < nbItems; i++) {
                if (i > 0 && i % 5 == 0) {
                    bulkRequest.add(new ElasticsearchIndexOperation(getCrawlerName(),
                            "" + i,
                            null,
                            "{\"foo\":{\"bar\":\"bar\"},\"number\":\"bar\"}"));
                    nbErrors++;
                } else {
                    bulkRequest.add(new ElasticsearchIndexOperation(getCrawlerName(),
                            "" + i,
                            null,
                            "{\"foo\":{\"bar\":\"bar\"},\"number\": " + i + "}"));
                }
            }

            ElasticsearchEngine engine = new ElasticsearchEngine(esClient);
            ElasticsearchBulkResponse bulkResponse = engine.bulk(bulkRequest);
            assertThat(bulkResponse.hasFailures(), is(true));
            assertThat(bulkResponse.getItems(), not(emptyIterable()));
            long errors = bulkResponse.getItems().stream().filter(FsCrawlerBulkResponse.BulkItemResponse::isFailed).count();
            assertThat(errors, is(nbErrors));

            // Wait until we have the expected number of documents indexed
            countTestHelper(new ESSearchRequest().withIndex(getCrawlerName()), nbItems - nbErrors, TimeValue.timeValueSeconds(10));
        }
    }

    @Test
    public void testDeleteSingle() throws Exception {
        esClient.indexSingle(getCrawlerName(), "1", "{ \"foo\": { \"bar\": \"bar\" } }", null);
        esClient.indexSingle(getCrawlerName(), "2", "{ \"foo\": { \"bar\": \"baz\" } }", null);
        esClient.indexSingle(getCrawlerName(), "3", "{ \"number\": 1 }", null);
        esClient.indexSingle(getCrawlerName(), "4", "{ \"number\": 2 }", null);

        ESSearchResponse response = countTestHelper(new ESSearchRequest().withIndex(getCrawlerName()), 4L, TimeValue.timeValueSeconds(10));
        assertThat(response.getTotalHits(), is(4L));

        esClient.deleteSingle(getCrawlerName(), "1");

        response = countTestHelper(new ESSearchRequest().withIndex(getCrawlerName()), 3L, TimeValue.timeValueSeconds(10));
        assertThat(response.getTotalHits(), is(3L));

        try {
            esClient.deleteSingle(getCrawlerName(), "99999");
            fail("We should have raised an " + ElasticsearchClientException.class.getSimpleName());
        } catch (ElasticsearchClientException e) {
            assertThat(e.getMessage(), is("Document " + getCrawlerName() + "/99999 does not exist"));
        }

        response = countTestHelper(new ESSearchRequest().withIndex(getCrawlerName()), 3L, TimeValue.timeValueSeconds(10));
        assertThat(response.getTotalHits(), is(3L));
    }

    @Test
    public void testExists() throws ElasticsearchClientException {
        esClient.indexSingle(getCrawlerName(), "1", "{ \"foo\": { \"bar\": \"bar\" } }", null);
        esClient.refresh(getCrawlerName());
        assertThat(esClient.exists(getCrawlerName(), "1"), is(true));
        assertThat(esClient.exists(getCrawlerName(), "999"), is(false));
    }

    @Test
    public void testWithOnlyOneRunningNode() throws ElasticsearchClientException, IOException {
        // Build a client with a non-running node
        FsSettings fsSettings = FsSettingsLoader.load();
        fsSettings.getElasticsearch().setNodes(List.of(
                new ServerUrl("http://127.0.0.1:9206"),
                new ServerUrl(testClusterUrl)));
        if (testApiKey != null) {
            fsSettings.getElasticsearch().setApiKey(testApiKey);
        } else if (testClusterUser != null && testClusterPass != null) {
            fsSettings.getElasticsearch().setUsername(testClusterUser);
            fsSettings.getElasticsearch().setPassword(testClusterPass);
        }
        fsSettings.getElasticsearch().setSslVerification(false);
        try (IElasticsearchClient localClient = new ElasticsearchClient(null, fsSettings)) {
            localClient.start();
            localClient.isExistingIndex("foo");
            localClient.isExistingIndex("bar");
            localClient.isExistingIndex("baz");
        }
    }

    @Test
    public void testWithTwoRunningNodes() throws ElasticsearchClientException, IOException {
        // Build a client with 2 running nodes (well, the same one is used twice) and one non-running node
        FsSettings fsSettings = FsSettingsLoader.load();
        fsSettings.getElasticsearch().setNodes(List.of(
                        new ServerUrl(testClusterUrl),
                        new ServerUrl(testClusterUrl),
                        new ServerUrl("http://127.0.0.1:9206"),
                        new ServerUrl(testClusterUrl)));
        if (testApiKey != null) {
            fsSettings.getElasticsearch().setApiKey(testApiKey);
        } else if (testClusterUser != null && testClusterPass != null) {
            fsSettings.getElasticsearch().setUsername(testClusterUser);
            fsSettings.getElasticsearch().setPassword(testClusterPass);
        }
        fsSettings.getElasticsearch().setSslVerification(false);
        fsSettings.getElasticsearch().setIndex(DOC_INDEX_NAME);
        fsSettings.getElasticsearch().setIndexFolder(FOLDER_INDEX_NAME);
        try (IElasticsearchClient localClient = new ElasticsearchClient(null, fsSettings)) {
            localClient.start();
            assertThat(localClient.getAvailableNodes(), hasSize(4));
            localClient.isExistingIndex("foo");
            assertThat(localClient.getAvailableNodes(), hasSize(3));

            for (int i = 0; i < CHECK_NODES_EVERY - 4; i++) {
                localClient.isExistingIndex("foo");
                assertThat("Run " + i, localClient.getAvailableNodes(), hasSize(3));
            }

            for (int i = 0; i < 10; i++) {
                localClient.isExistingIndex("foo");
                assertThat(localClient.getAvailableNodes(), hasSize(4));
                localClient.isExistingIndex("foo");
                assertThat("Run " + i, localClient.getAvailableNodes(), hasSize(4));
                localClient.isExistingIndex("foo");
                assertThat("Run " + i, localClient.getAvailableNodes(), hasSize(3));
                for (int j = 0; j < CHECK_NODES_EVERY - 4; j++) {
                    localClient.isExistingIndex("foo");
                    assertThat("Run " + i + "-" + j, localClient.getAvailableNodes(), hasSize(3));
                }
            }
        }
    }

    @Test
    public void testWithNonRunningNodes() {
        // Build a client with a non-running node
        FsSettings fsSettings = FsSettingsLoader.load();
        fsSettings.getElasticsearch().setNodes(List.of(
                new ServerUrl("http://127.0.0.1:9206"),
                new ServerUrl("http://127.0.0.1:9207")));
        fsSettings.getElasticsearch().setUsername(DEFAULT_USERNAME);
        fsSettings.getElasticsearch().setPassword(DEFAULT_PASSWORD);
        fsSettings.getElasticsearch().setSslVerification(false);

        try (IElasticsearchClient localClient = new ElasticsearchClient(null, fsSettings)) {
            localClient.start();
            fail("We should have raised a " + ElasticsearchClientException.class.getSimpleName());
        } catch (IOException ex) {
            fail("We should have raised a " + ElasticsearchClientException.class.getSimpleName());
        } catch (ElasticsearchClientException ex) {
            assertThat(ex.getMessage(), containsString("All nodes are failing"));
        }
    }

    @Test
    public void testWithNonRunningNode() {
        // Build a client with a non-running node
        FsSettings fsSettings = FsSettingsLoader.load();
        fsSettings.getElasticsearch().setNodes(List.of(new ServerUrl("http://127.0.0.1:9206")));
        fsSettings.getElasticsearch().setUsername(DEFAULT_USERNAME);
        fsSettings.getElasticsearch().setPassword(DEFAULT_PASSWORD);
        fsSettings.getElasticsearch().setSslVerification(false);

        try (IElasticsearchClient localClient = new ElasticsearchClient(null, fsSettings)) {
            localClient.start();
            fail("We should have raised a " + ElasticsearchClientException.class.getSimpleName());
        } catch (IOException ex) {
            fail("We should have raised a " + ElasticsearchClientException.class.getSimpleName());
        } catch (ElasticsearchClientException ex) {
            assertThat(ex.getMessage(), containsString("Can not execute GET"));
            assertThat(ex.getCause().getCause(), instanceOf(ConnectException.class));
            assertThat(ex.getCause().getCause().getMessage(), containsString("Connection refused"));
        }
    }

    @Test
    public void testSecuredClusterWithBadCredentials() throws IOException, ElasticsearchClientException {
        // Build a client with a null password
        FsSettings fsSettings = FsSettingsLoader.load();
        fsSettings.getElasticsearch().setNodes(List.of(new ServerUrl(testClusterUrl)));
        fsSettings.getElasticsearch().setSslVerification(false);

        try (IElasticsearchClient localClient = new ElasticsearchClient(null, fsSettings)) {
            localClient.start();
            fail("We should have raised a " + ElasticsearchClientException.class.getSimpleName());
        } catch (NotAuthorizedException ex) {
            assertThat(ex.getMessage(), containsString("HTTP 401 Unauthorized"));
        }
    }

    @Test
    public void createApiKey() {
        // This is not a critical one as this code is only used in tests
        try {
            String key = esClient.generateApiKey("fscrawler-es-client-test");
            assertThat(key, notNullValue());
        } catch (Exception e) {
            // creating derived api keys requires an explicit role descriptor that is empty (has no privileges)
            logger.warn("Can not create an API Key. " +
                    "This is not a critical one as this code is only used in tests. So we skip it.", e);
        }
    }

    @Test
    public void testWithHttpService() throws IOException, ElasticsearchClientException {
        logger.debug("Starting Nginx from {}", rootTmpDir);

        // First we call Elasticsearch client
        assertThat(esClient.getVersion(), not(isEmptyOrNullString()));

        Path nginxRoot = rootTmpDir.resolve("nginx-root");
        Files.createDirectory(nginxRoot);
        Files.writeString(nginxRoot.resolve("index.html"), "<html><body>Hello World!</body></html>");

        try (NginxContainer<?> container = new NginxContainer<>("nginx")) {
            container.waitingFor(new HttpWaitStrategy());
            container.start();
            container.copyFileToContainer(MountableFile.forHostPath(nginxRoot), "/usr/share/nginx/html");
            URL url = container.getBaseUrl("http", 80);
            logger.debug("Nginx started on {}.", url);

            InputStream inputStream = url.openStream();
            String text = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
            assertThat(text, containsString("Hello World!"));
        }

        // Then we call Elasticsearch client again
        assertThat(esClient.getVersion(), not(isEmptyOrNullString()));
    }

    @Test
    public void license() throws ElasticsearchClientException {
        String license = esClient.getLicense();
        assertThat(license, not(isEmptyOrNullString()));
    }

    @Test
    public void testIndexFsCrawlerDocuments() throws Exception {
        // Remove existing templates if any
        removeIndexTemplates();
        removeComponentTemplates();

        // We push the templates to the cluster
        esClient.createIndexAndComponentTemplates();

        // We remove the exising indices
        esClient.deleteIndex(DOC_INDEX_NAME);
        esClient.deleteIndex(FOLDER_INDEX_NAME);

        // We create a document
        esClient.index(DOC_INDEX_NAME, "BackToTheFuture", new Doc("Marty! Let's go back to the future!"), null);
        esClient.index(DOC_INDEX_NAME, "StarWars", new Doc("Luke. Obiwan never told you what happened to your father. I'm your father!"), null);
        esClient.index(DOC_INDEX_NAME, "TheLordOfTheRings", new Doc("You cannot pass! I am a servant of the Secret Fire, wielder of the Flame of Anor. The dark fire will not avail you, Flame of Udun! Go back to the shadow. You shall not pass!"), null);

        // We flush the bulk request
        esClient.flush();

        // Wait until we have the expected number of documents indexed
        countTestHelper(new ESSearchRequest().withIndex(DOC_INDEX_NAME), 3L, TimeValue.timeValueSeconds(10));

        // We can run some queries to check that semantic search actually works as expected
        assertThat(esClient.search(new ESSearchRequest()
                .withIndex(DOC_INDEX_NAME)
                .withESQuery(new ESMatchQuery("content", "father"))
        ).getHits().get(0).getId(), is("StarWars"));
        assertThat(esClient.search(new ESSearchRequest()
                .withIndex(DOC_INDEX_NAME)
                .withESQuery(new ESMatchQuery("content", "future"))
        ).getHits().get(0).getId(), is("BackToTheFuture"));
        assertThat(esClient.search(new ESSearchRequest()
                .withIndex(DOC_INDEX_NAME)
                .withESQuery(new ESMatchQuery("content", "Flame"))
        ).getHits().get(0).getId(), is("TheLordOfTheRings"));

        // We can only execute this test when semantic search is available
        if (esClient.isSemanticSupported()) {
            // We can run some queries to check that semantic search actually works as expected
            assertThat(esClient.search(new ESSearchRequest()
                    .withIndex(DOC_INDEX_NAME)
                    .withESQuery(new ESSemanticQuery("content_semantic", "a movie from Georges Lucas"))
            ).getHits().get(0).getId(), is("StarWars"));
            assertThat(esClient.search(new ESSearchRequest()
                    .withIndex(DOC_INDEX_NAME)
                    .withESQuery(new ESSemanticQuery("content_semantic", "a movie with a delorean car"))
            ).getHits().get(0).getId(), is("BackToTheFuture"));
            assertThat(esClient.search(new ESSearchRequest()
                    .withIndex(DOC_INDEX_NAME)
                    .withESQuery(new ESSemanticQuery("content_semantic", "Frodo and Gollum"))
            ).getHits().get(0).getId(), is("TheLordOfTheRings"));
        }
    }

    private void removeComponentTemplates() {
        logger.debug("Removing component templates");
        try {
            esClient.performLowLevelRequest("DELETE", "/_component_template/fscrawler_*", null);
        } catch (ElasticsearchClientException | NotFoundException e) {
            // We ignore the error
        }
    }

    private void removeIndexTemplates() {
        logger.debug("Removing index templates");
        try {
            esClient.performLowLevelRequest("DELETE", "/_index_template/fscrawler_*", null);
        } catch (ElasticsearchClientException | NotFoundException e) {
            // We ignore the error
        }
    }

    protected String getCrawlerName() {
        String testName = "fscrawler_".concat(getCurrentClassName()).concat("_").concat(getCurrentTestName());
        return testName.contains(" ") ? split(testName, " ")[0] : testName;
    }

    /**
     * Check that we have the expected number of docs or at least one if expected is null
     *
     * @param request   Elasticsearch request to run.
     * @param expected  expected number of docs. Null if at least 1.
     * @param timeout   Time before we declare a failure
     * @return the search response if further tests are needed
     * @throws Exception in case of error
     */
    public ESSearchResponse countTestHelper(final ESSearchRequest request, final Long expected, final TimeValue timeout) throws Exception {

        final ESSearchResponse[] response = new ESSearchResponse[1];

        // We wait before considering a failing test
        logger.info("  ---> Waiting up to {} for {} documents in {}", timeout.toString(),
                expected == null ? "some" : expected, request.getIndex());
        long hits = awaitBusy(() -> {
            long totalHits;

            // Let's search for entries
            try {
                // Make sure we refresh indexed docs before counting
                esClient.refresh(request.getIndex());
                response[0] = esClient.search(request);
            } catch (RuntimeException e) {
                logger.warn("error caught", e);
                return -1;
            } catch (ElasticsearchClientException e) {
                // TODO create a NOT FOUND Exception instead
                logger.debug("error caught", e);
                return -1;
            }
            totalHits = response[0].getTotalHits();

            logger.debug("got so far [{}] hits on expected [{}]", totalHits, expected);

            return totalHits;
        }, expected, timeout);

        Matcher<Long> matcher;
        if (expected == null) {
            matcher = greaterThan(0L);
        } else {
            matcher = equalTo(expected);
        }

        logger.log(matcher.matches(hits) ? Level.DEBUG : Level.WARN, "     ---> expecting [{}] and got [{}] documents in {}", expected, hits, request.getIndex());
        assertThat(hits, matcher);

        return response[0];
    }
}