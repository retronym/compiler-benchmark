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

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class UploadingOutputFormat extends DelegatingOutputFormat {
    public UploadingOutputFormat(OutputFormat delegate) {
        super(delegate);
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
            pointBuilder.addField("score", result.getPrimaryResult().getScore());
            pointBuilder.addField("sampleCount", result.getPrimaryResult().getSampleCount());
            for (String key : paramsKeys) {
                pointBuilder.addField(key, params.getParam(key));
            }

            String scalaVersion = params.getParam("_scalaRef");
            Objects.requireNonNull(scalaVersion, "\"_scalaRef\" parameter not found among " + paramsKeys);
            Config conf = ConfigFactory.load();
            String hostUUID = conf.getString("host.uuid");
            pointBuilder.tag("hostUUID", hostUUID);

            RevWalk walk = new RevWalk(repo);
            RevCommit revCommit = walk.parseCommit(repo.resolve(scalaVersion));
            pointBuilder.time(revCommit.getCommitTime(), TimeUnit.SECONDS);

            batchPoints.point(pointBuilder.build());
            influxDB.write(batchPoints);
        } catch (Exception e) {
            Throwables.throwIfUnchecked(e);
            throw new RuntimeException(e);
        } finally {
            influxDB.close();
        }
    }
}
