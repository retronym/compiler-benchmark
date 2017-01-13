package scala.bench;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.BatchPoints;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

public class ResultPersister {
    public static void main(String[] args) throws IOException, GitAPIException {
        Repository gitRepo = openGit();
        InfluxDB influxDB = connectDb();


        BatchPoints points = BatchPoints.database("scala_benchmark").build();
        influxDB.write(points);

        try {
            GitMetadataUploader uploader = new GitMetadataUploader(gitRepo, influxDB);
            uploader.upload("2.13.x", "2.12.x");
            uploader.upload("2.12.x", "2.11.x");
            uploader.upload("2.11.x", "2.10.x");
            uploader.upload("2.10.x", "2.9.x");
            uploader.upload("2.9.x", "2.8.x");
        } finally {
            influxDB.close();
            gitRepo.close();
        }
    }

    private static Repository openGit() throws IOException {
        return new FileRepositoryBuilder().setGitDir(Paths.get("/code/scala").resolve(".git").toFile())
                .readEnvironment() // Do we need this?
                .findGitDir()
                .build();
    }

    private static InfluxDB connectDb() {
        String influxUrl = "https://scala-ci.typesafe.com/influx/";
        String influxUser = "scala";
        String influxPassword = System.getenv("INFLUX_PASSWORD");

        OkHttpClient.Builder client = new OkHttpClient.Builder();

        // workaround https://github.com/influxdata/influxdb-java/issues/268
        client.addNetworkInterceptor(chain -> {
            HttpUrl.Builder fixedUrl = chain.request().url().newBuilder().encodedPath("/influx/" + chain.request().url().encodedPath().replaceFirst("/influxdb", ""));
            return chain.proceed(chain.request().newBuilder().url(fixedUrl.build()).build());
        });

        client.authenticator((route, response) -> {
            String credential = Credentials.basic(influxUser, influxPassword);
            return response.request().newBuilder()
                    .header("Authorization", credential)
                    .build();
        });
        InfluxDB influxDB = InfluxDBFactory.connect(influxUrl, influxUser, influxPassword, client);
        influxDB.setLogLevel(InfluxDB.LogLevel.FULL);
        return influxDB;
    }
}
