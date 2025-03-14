package hudson.plugins.sauce_ondemand;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.gargoylesoftware.htmlunit.html.DomElement;
import com.gargoylesoftware.htmlunit.html.DomNodeList;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.saucelabs.saucerest.JobSource;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.maven.MavenBuild;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.Build;
import hudson.model.FreeStyleProject;
import hudson.plugins.sauce_ondemand.credentials.SauceCredentials;
import hudson.plugins.sauce_ondemand.mocks.MockSauceREST;
import org.apache.commons.io.IOUtils;
import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SingleFileSCM;

import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@SuppressFBWarnings("SIC_INNER_SHOULD_BE_STATIC_ANON")
public class SauceOnDemandBuildActionTest {
    @ClassRule
    public static JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void doJobReportTest() throws Exception {
        final JenkinsSauceREST mockSauceREST = mock(MockSauceREST.class);
        when(mockSauceREST.getFullJobsByIds(anyList())).thenReturn(
            IOUtils.toString(SauceOnDemandProjectActionTest.class.getResourceAsStream("/build_jobs.json"), "UTF-8")
        );
        when(mockSauceREST.getTunnels()).thenReturn("[]");

        FreeStyleProject freeStyleProject = jenkins.createFreeStyleProject();
        TestSauceOnDemandBuildWrapper bw = new TestSauceOnDemandBuildWrapper(
            SauceCredentials.migrateToCredentials("fakeuser", "fakekey", null, "unittest")
        );
        bw.setEnableSauceConnect(false);
        freeStyleProject.getBuildWrappersList().add(bw);
        Build build = freeStyleProject.scheduleBuild2(0).get();
        SauceOnDemandBuildAction buildAction = new SauceOnDemandBuildAction(build, bw.getCredentialId()) {
            @Override
            protected JenkinsSauceREST getSauceREST() {
                //mockSauceREST.setServer("http://localhost:61325"); not the failure
                return mockSauceREST;
            }
        };
        build.addAction(buildAction);

        JenkinsRule.WebClient webClient = jenkins.createWebClient();
        webClient.setJavaScriptEnabled(false);
        HtmlPage page = webClient.getPage(build, "sauce-ondemand-report/jobReport?jobId=1234");
        jenkins.assertGoodStatus(page);

        DomElement scriptTag = getEmbedTag(page.getElementsByTagName("iframe"));
        assertThat(new URL(scriptTag.getAttribute("src")).getPath(), endsWith("/job-embed/1234"));
        assertThat(new URL(scriptTag.getAttribute("src")).getQuery(), containsString("auth="));
        verifyNoMoreInteractions(mockSauceREST);
    }

    @Test
    public void testGetSauceBuildAction () throws Exception {
        FreeStyleProject freeStyleProject = jenkins.createFreeStyleProject();
        TestSauceOnDemandBuildWrapper bw = new TestSauceOnDemandBuildWrapper(
            SauceCredentials.migrateToCredentials("fakeuser", "fakekey", null, "unittest")
        );
        bw.setEnableSauceConnect(false);
        freeStyleProject.getBuildWrappersList().add(bw);
        Build build = freeStyleProject.scheduleBuild2(0).get();
        SauceOnDemandBuildAction buildAction = new SauceOnDemandBuildAction(build, bw.getCredentialId());
        build.addAction(buildAction);
        SauceOnDemandBuildAction  sauceBuildAction= SauceOnDemandBuildAction.getSauceBuildAction(build);
        assertEquals("fakeuser", sauceBuildAction.getCredentials().getUsername());
    }

    @Test
    public void testGetSauceBuildActionMavenBuild() throws Exception {
        MavenModuleSet project = jenkins.createProject(MavenModuleSet.class, "testGetSauceBuildActionMavenBuild");
        TestSauceOnDemandBuildWrapper bw = new TestSauceOnDemandBuildWrapper(
            SauceCredentials.migrateToCredentials("fakeuser", "fakekey", null, "unittest")
        );
        project.getBuildWrappersList().add(bw);
        project.setScm(new SingleFileSCM("pom.xml",getClass().getResource("/pom.xml")));
        project.setGoals("clean");
        MavenModuleSetBuild build =  project.scheduleBuild2(0).get(1, TimeUnit.MINUTES);
        SauceOnDemandBuildAction buildAction = new SauceOnDemandBuildAction(build, bw.getCredentialId());
        build.addAction(buildAction);
        final MavenBuild mavenBuildMock = mock(MavenBuild.class);
        when(mavenBuildMock.getParentBuild()).thenReturn(build);
        SauceOnDemandBuildAction sauceBuildAction = SauceOnDemandBuildAction.getSauceBuildAction(mavenBuildMock);
        assertEquals("fakeuser", sauceBuildAction.getCredentials().getUsername());

    }

    @Test
    public void testRetrieveJobIdsFromSauce() throws Exception {
        final Build build = makeMavenBuild();
        final SauceCredentials credentials = makeSauceCredentials();
        final JenkinsSauceREST mockSauceREST = mock(MockSauceREST.class);
        final int jobCount = 4;
        final List<String> jobIds = makeJobIds(jobCount);
        final String buildJobsResponse = makeJobsResponse(jobIds, "/build_jobs_v2.json");
        final String jobsResponse = makeJobListResponse(jobIds, "/jobs_by_ids.json");
        when(mockSauceREST.getBuildsByName(any(JobSource.class), anyString(), anyInt())).thenReturn(
            IOUtils.toString(SauceOnDemandProjectActionTest.class.getResourceAsStream("/builds_by_name.json"), "UTF-8")
        );
        when(mockSauceREST.getBuildJobs(any(JobSource.class), anyString())).thenReturn(buildJobsResponse);
        when(mockSauceREST.getFullJobsByIds(anyList())).thenReturn(jobsResponse);

        LinkedHashMap<String, JenkinsJobInformation> jobInformation = SauceOnDemandBuildAction.retrieveJobIdsFromSauce(mockSauceREST, build, credentials);

        Set<String> jobIdsSet = new HashSet<String>(jobIds);
        assertEquals(jobInformation.keySet(), jobIdsSet);
    }

    @Test
    public void testRetrieveJobIdsFromSauceSplitsCallsToResto() throws Exception {
        final Build build = makeMavenBuild();
        final SauceCredentials credentials = makeSauceCredentials();
        final JenkinsSauceREST mockSauceREST = mock(MockSauceREST.class);
        final int jobCount = 30;
        final List<String> jobIds = makeJobIds(jobCount);
        final String buildJobsResponse = makeJobsResponse(jobIds, "/build_jobs_v2.json");
        final String jobsResponse1 = makeJobListResponse(jobIds.subList(0, 20), "/jobs_by_ids.json");
        final String jobsResponse2 = makeJobListResponse(jobIds.subList(20, jobIds.size()), "/jobs_by_ids.json");
        when(mockSauceREST.getBuildsByName(any(JobSource.class), anyString(), anyInt())).thenReturn(
            IOUtils.toString(SauceOnDemandProjectActionTest.class.getResourceAsStream("/builds_by_name.json"), "UTF-8")
        );
        when(mockSauceREST.getBuildJobs(any(JobSource.class), anyString())).thenReturn(buildJobsResponse);
        when(mockSauceREST.getFullJobsByIds(anyList())).thenReturn(jobsResponse1).thenReturn(jobsResponse2);

        LinkedHashMap<String, JenkinsJobInformation> jobInformation = SauceOnDemandBuildAction.retrieveJobIdsFromSauce(mockSauceREST, build, credentials);

        Set<String> jobIdsSet = new HashSet<String>(jobIds);
        assertEquals(jobInformation.keySet(), jobIdsSet);
    }

    @Test
    public void testRetrieveJobIdsFromSauceIfBuildIsNotFound() throws Exception {
        final Build build = makeMavenBuild();
        final SauceCredentials credentials = makeSauceCredentials();
        final JenkinsSauceREST mockSauceREST = mock(MockSauceREST.class);
        when(mockSauceREST.getBuildsByName(any(JobSource.class), anyString(), anyInt())).thenReturn(
                "{\"builds\":[]}"
        );

        LinkedHashMap<String, JenkinsJobInformation> jobInformation = SauceOnDemandBuildAction.retrieveJobIdsFromSauce(mockSauceREST, build, credentials);

        assertEquals(jobInformation.keySet().size(), 0);
    }

    private List<String> makeJobIds(int count) {
        List<String> jobIds = new ArrayList<String>();
        for (int i = 0; i < count; i++)
            jobIds.add(String.format("%032x", i + 1));
        return jobIds;
    }

    private SauceCredentials makeSauceCredentials() {
        return new SauceCredentials(
            CredentialsScope.GLOBAL,
            "credentials-id",
            "fakeuser",
            "fake-access-key",
            "localhost",
            ""
        );
    }

    private Build makeMavenBuild() throws Exception {
        FreeStyleProject freeStyleProject = jenkins.createFreeStyleProject();
        TestSauceOnDemandBuildWrapper bw = new TestSauceOnDemandBuildWrapper(
            SauceCredentials.migrateToCredentials("fakeuser", "fakekey", null, "unittest")
        );
        bw.setEnableSauceConnect(false);
        freeStyleProject.getBuildWrappersList().add(bw);
        return freeStyleProject.scheduleBuild2(0).get();
    }

    private String makeJobsResponse(List<String> ids, String fileName) throws Exception {
        String buildJobsV2Response = IOUtils.toString(
                SauceOnDemandProjectActionTest.class.getResourceAsStream(fileName), "UTF-8");
        JSONObject jsonResponse = new JSONObject(buildJobsV2Response);
        JSONArray jobs = jsonResponse.getJSONArray("jobs");
        JSONObject job = jobs.getJSONObject(0);
        while (jobs.length() > 0)
            jobs.remove(0);
        for (String jobId: ids) {
            JSONObject cloned = new JSONObject(job.toMap());
            cloned.put("id", jobId);
            jobs.put(cloned);
        }
        return jsonResponse.toString();
    }

    private String makeJobListResponse(List<String> ids, String fileName) throws Exception {
        String buildJobsV2Response = IOUtils.toString(
                SauceOnDemandProjectActionTest.class.getResourceAsStream(fileName), "UTF-8");
        JSONArray jobs = new JSONArray(buildJobsV2Response);
        JSONObject job = jobs.getJSONObject(0);
        while (jobs.length() > 0)
            jobs.remove(0);
        for (String jobId: ids) {
            JSONObject cloned = new JSONObject(job.toMap());
            cloned.put("id", jobId);
            jobs.put(cloned);
        }
        return jobs.toString();
    }

    private DomElement getEmbedTag(DomNodeList<DomElement> scripts) {
        for(DomElement htmlElement : scripts)
        {
            if (htmlElement.getAttribute("src").contains("job-embed")) {
                return htmlElement;
            }
        }
        return null;
    }
}
