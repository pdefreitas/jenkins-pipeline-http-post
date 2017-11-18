package com.pdefreitas.jenkins;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.MultipartBuilder;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import hudson.Extension;
import hudson.Launcher;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;

import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import jenkins.tasks.SimpleBuildStep;

/**
 * Upload all {@link hudson.model.Run.Artifact artifacts} using a multipart HTTP POST call to an
 * specific URL.<br> Additional metadata will be included in the request as HTTP headers: {@code
 * Job-Name}, {@code Build-Number} and {@code Build-Timestamp} are included automatically by the
 * time writing.
 *
 * @author Christian Becker (christian.becker.1987@gmail.com)
 */
//@SuppressWarnings("UnusedDeclaration") // This class will be loaded using its Descriptor.
public class PipelineHttpPostPublisher extends Notifier implements SimpleBuildStep {

  private String url;
  private String headers;

  @DataBoundConstructor
  public PipelineHttpPostPublisher(String url) {
    this.url = url;
    this.headers = "";
  }

  /**
   * @return the url
   */
  public String getUrl() {
    return url;
  }

  /**
   * @param headers the headers to set
   */
  @DataBoundSetter
  public void setHeaders(String headers) {
    this.headers = headers;
  }

  /**
   * @return the headers
   */
  public String getHeaders() {
    return this.headers;
  }

  @Override
  public void perform(Run<?, ?> run, FilePath filePath, Launcher launcher, TaskListener taskListener) {
    if(run == null) {
      taskListener.getLogger().println("HTTP POST: Empty run value");      
      return;
    }
    
    try {
      performRun(run, filePath, launcher, taskListener);
    } catch (Exception e) {
      e.printStackTrace(taskListener.getLogger());
    }
  }

  public void performRun(Run<?, ?> run, FilePath filePath, Launcher launcher, TaskListener taskListener) {    
    //Map<String, String> envs = run instanceof AbstractBuild ? ((AbstractBuild<?,?>) run).getBuildVariables() : Collections.<String, String>emptyMap();
    try {
      if (run.getArtifacts().isEmpty()) {
        taskListener.getLogger().println("HTTP POST: No artifacts to POST");
        return;
      }

      //Descriptor descriptor = getDescriptor();
      if (this.url == null) {
        taskListener.getLogger().println("HTTP POST: No URL specified");
        return;
      }

      MultipartBuilder multipart = new MultipartBuilder();
      multipart.type(MultipartBuilder.FORM);
      for (Run.Artifact artifact : run.getArtifacts()) {
        multipart.addFormDataPart(artifact.getFileName(), artifact.getFileName(),
            RequestBody.create(null, artifact.getFile()));
      }

      OkHttpClient client = new OkHttpClient();
      client.setConnectTimeout(30, TimeUnit.SECONDS);
      client.setReadTimeout(60, TimeUnit.SECONDS);

      Request.Builder builder = new Request.Builder();
      builder.url(url);
      builder.header("Job-Name", run.getParent().getName());
      builder.header("Build-Number", String.valueOf(run.getNumber()));
      builder.header("Build-Timestamp", String.valueOf(run.getTimeInMillis()));
      if (headers != null && headers.length() > 0) {
        String[] lines = headers.split("\r?\n");
        for (String line : lines) {
          int index = line.indexOf(':');
          builder.header(line.substring(0, index).trim(), line.substring(index + 1).trim());
        }
      }
      builder.post(multipart.build());

      Request request = builder.build();
      taskListener.getLogger().println(String.format("---> POST %s", url));
      taskListener.getLogger().println(request.headers());

      long start = System.nanoTime();
      Response response = client.newCall(request).execute();
      long time = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      taskListener.getLogger()
          .println(String.format("<--- %s %s (%sms)", response.code(), response.message(), time));
      taskListener.getLogger().println(response.body().string());
    } catch (Exception e) {
      e.printStackTrace(taskListener.getLogger());
    }
  }

  @Override
  public BuildStepMonitor getRequiredMonitorService() {
    return BuildStepMonitor.NONE;
  }

  @Override
  public Descriptor getDescriptor() {
    return (Descriptor) super.getDescriptor();
  }

  @Extension
  public static final class Descriptor extends BuildStepDescriptor<Publisher> {

    public Descriptor() {
      load();
    }

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> jobType) {
      return true;
    }

    @Override
    public String getDisplayName() {
      return "HTTP POST artifacts to an URL";
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
      req.bindJSON(this, json.getJSONObject("http-post"));
      save();

      return true;
    }
  }
}
