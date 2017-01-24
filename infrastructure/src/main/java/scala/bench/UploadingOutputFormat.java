package scala.bench;

import com.google.common.base.Throwables;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.influxdb.InfluxDB;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.runner.format.OutputFormat;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class UploadingOutputFormat extends DelegatingOutputFormat {

    private final GitMetadataUploader gitMetadataUploader;

    public UploadingOutputFormat(OutputFormat delegate) {
        super(delegate);
        Repository repository = ResultPersister.openGit();
        gitMetadataUploader = new GitMetadataUploader(repository);
        gitMetadataUploader.createAllPoints();
    }

    @Override
    public void endBenchmark(BenchmarkResult result) {
        super.endBenchmark(result);

        try {
            uploadResult(result);
        } catch (RuntimeException ex){
            System.err.println(Throwables.getStackTraceAsString(ex));
        }
    }

    private void uploadResult(BenchmarkResult result) {
        InfluxDB influxDB = ResultPersister.connectDb();
        try (Repository repo = ResultPersister.openGit()) {
            BatchPoints batchPoints = BatchPoints
                    .database("scala_benchmark")
                    .retentionPolicy("autogen")
                    .consistency(InfluxDB.ConsistencyLevel.ALL)
                    .build();
            Point.Builder pointBuilder = Point.measurement("result");
            BenchmarkParams params = result.getParams();
            Collection<String> paramsKeys = params.getParamsKeys();
            pointBuilder.tag("label", result.getPrimaryResult().getLabel());
            pointBuilder.tag("benchmark", result.getParams().getBenchmark().replace("scala.tools.nsc.", ""));
            for (String key : paramsKeys) {
                String value = params.getParam(key);
                if (value != null && !value.isEmpty()) {
                    pointBuilder.tag(key, value);
                }
            }
            pointBuilder.addField("score", result.getPrimaryResult().getScore());
            pointBuilder.addField("sampleCount", result.getPrimaryResult().getSampleCount());
            pointBuilder.addField("scoreError", result.getPrimaryResult().getScoreError());
            pointBuilder.addField("scoreUnit", result.getPrimaryResult().getScoreUnit());

            String scalaVersion = System.getProperty("scalaVersion");
            String scalaRef = System.getProperty("scalaRef");
            if (scalaRef == null) {
                List<String> inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
                System.out.println("input arguments = " + inputArguments);
                throw new RuntimeException("Please provide -DscalaRef=...\n\n" + inputArguments);
            }
            Config conf = ConfigFactory.load();
            pointBuilder.tag("branch", gitMetadataUploader.branchOfRef(scalaRef));
            pointBuilder.tag("hostId", getHostId());
            pointBuilder.addField("scalaVersion", scalaVersion);

            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit revCommit = walk.parseCommit(repo.resolve(scalaRef));
                pointBuilder.time(revCommit.getCommitTime(), TimeUnit.SECONDS);
                batchPoints.point(pointBuilder.build());
                influxDB.write(batchPoints);
                System.out.println("Uploaded " + batchPoints.getPoints().size()+ " points to benchmark database for " + scalaRef + "/" + revCommit.getName() + ", " + revCommit.getCommitTime() + "s");
            }
        } catch (Exception e) {
            Throwables.throwIfUnchecked(e);
            throw new RuntimeException(e);
        } finally {
            influxDB.close();
        }
    }

    private static String getHostId() throws Exception {
        StringBuilder hostId = new StringBuilder();
        hostId.append(InetAddress.getLocalHost().getHostName());
        String mac = getHardwareAddress();
        if (mac != null) {
            hostId.append("@").append(hostId);
        }
        return hostId.toString();
    }

    private static String getHardwareAddress() throws Exception {
        InetAddress ip = InetAddress.getLocalHost();
        NetworkInterface ni = NetworkInterface.getByInetAddress(ip);
        if (!ni.isVirtual() && !ni.isLoopback() && !ni.isPointToPoint() && ni.isUp()) {
            final byte[] bb = ni.getHardwareAddress();
            StringBuilder builder = new StringBuilder();
            for (byte b : bb) {
                builder.append(String.format("%02X", b));
            }
            return builder.toString();
        }
        return null;
    }
}
