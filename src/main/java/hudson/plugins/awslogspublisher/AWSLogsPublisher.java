package hudson.plugins.awslogspublisher;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.internal.StaticCredentialsProvider;
import com.amazonaws.services.logs.AWSLogs;
import com.amazonaws.services.logs.AWSLogsClientBuilder;
import com.amazonaws.services.logs.model.CreateLogStreamRequest;
import com.amazonaws.services.logs.model.InputLogEvent;
import com.amazonaws.services.logs.model.PutLogEventsRequest;
import com.amazonaws.services.logs.model.PutLogEventsResult;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.plugins.timestamper.api.TimestamperAPI;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.BufferedReader;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;


/**
 * When a publisher is performed, the {@link #perform(AbstractBuild, Launcher, BuildListener)}
 * method will be invoked.
 *
 * @author Elifarley Cruz
 */
public class AWSLogsPublisher extends Recorder {

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public AWSLogsPublisher() {
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    /**
     * Actually publish the Console Logs to the workspace.
     */
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {

        //if (!writeConsoleLog) return true;

        final AWSLogsConfig config = AWSLogsConfig.get();

        AWSCredentials credentials = new AWSCredentials() {

            @Override
            public String getAWSAccessKeyId() {
                return config.getAwsAccessKeyId();
            }

            @Override
            public String getAWSSecretKey() {
                return config.getAwsSecretKey();
            }
        };

        AWSLogs awsLogs = AWSLogsClientBuilder.standard().
                withRegion(config.getAwsRegion()).
                withCredentials(new StaticCredentialsProvider(credentials)).
                build();


        try {
            pushToAWSLogs(build, awsLogs, config.getLogGroupName());

        } catch (IOException | InterruptedException e) {
            build.setResult(Result.UNSTABLE);
        }
        return true;
    }

    private void pushToAWSLogs(AbstractBuild build, AWSLogs awsLogs, String logGroupName)
            throws IOException, InterruptedException {

        String logStreamName = build.getProject().getName() + "/" + build.getNumber();
        awsLogs.createLogStream(new CreateLogStreamRequest(logGroupName, logStreamName));

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd.HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        String query = "time=yyyy-MM-dd.HH:mm:ss&timeZone=UTC&appendLog";
        try (BufferedReader reader = TimestamperAPI.get().read(build, query)) {

            List<InputLogEvent> list = new ArrayList<>();
            String line;
            // TODO Max 10k lines
            int count = 0;
            while ((line = reader.readLine()) != null) {
                Long timestamp = dateFormat.parse(line.substring(0, 19)).getTime();
                line = line.substring(21);
                list.add(new InputLogEvent().withMessage(line).withTimestamp(timestamp));
            }

            PutLogEventsResult logEventsResult = awsLogs.putLogEvents(new PutLogEventsRequest(logGroupName, logStreamName, list));
            String nextSequenceToken = logEventsResult.getNextSequenceToken();

        } catch (ParseException e) {
            throw new RuntimeException(e);
        }


    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * Descriptor for {@link AWSLogsPublisher}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     * <p>
     * <p>
     * See <tt>src/main/resources/hudson/plugins/awslogspublisher/AWSLogsPublisher/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "AWS Logs Publisher";
        }

        /**
         * Performs on-the-fly validation of the form field 'name'.
         *
         * @param value This parameter receives the value that the user has typed.
         * @return Indicates the outcome of the validation. This is sent to the browser.
         */
        public FormValidation doCheckFileName(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set an output file name");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

    }
}
