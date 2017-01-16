package scala.bench;

import com.google.common.escape.Escaper;
import com.google.common.html.HtmlEscapers;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.influxdb.InfluxDB;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class GitMetadataUploader {
    private Repository repo;
    private InfluxDB influxDB;

    public GitMetadataUploader(Repository repo, InfluxDB influxDB) {
        this.repo = repo;
        this.influxDB = influxDB;
    }

    public void uploadAllGitMetadata() {
        // forkPoint parameter generated manually with:
        // git log --topo-order --first-parent --oneline 2.13.x --not 2.12.x | tail -1
        // TODO find this with JGit.
        upload("2.13.x", "2.12.x", "fc1aea6712");
        upload("2.12.x", "2.11.x", "132a0587ab");
        upload("2.11.x", "2.10.x", "7ac15a1210");
        upload("2.10.x", "2.9.x", "cc672b023e");
        upload("2.9.x", "2.8.x", "33e1dac4e4");
    }

    public void upload(String branch, String prevBranch, String forkPoint) {
        BatchPoints batchPoints = BatchPoints
                .database("scala_benchmark")
                .retentionPolicy("autogen")
                .consistency(InfluxDB.ConsistencyLevel.ALL)
                .build();
        try {
            ObjectId resolvedBranch = resolve(branch);
            ObjectId resolvedPrevBranch = resolve(prevBranch);
            ObjectId resolvedForkPoint = resolve(forkPoint);

            RevWalk walk = new RevWalk(repo);
            RevCommit revCommit = walk.parseCommit(resolvedBranch);
            while (!resolvedForkPoint.getName().equals(revCommit.getName())) {
                Escaper escaper = HtmlEscapers.htmlEscaper();
                String commiterName = revCommit.getCommitterIdent().getName();

                // workaround (?) https://github.com/influxdata/influxdb-java/issues/269
                String sanitizedMessage = revCommit.getFullMessage().replace("\\", "\\\\");
                String annotationHtml = String.format(
                        "<a href='https://github.com/scala/scala/commit/%s'>%s</a><p>%s<p><pre>%s</pre>",
                        revCommit.name(),
                        revCommit.name().substring(0, 10),
                        escaper.escape(commiterName),
                        escaper.escape(StringUtils.abbreviate(sanitizedMessage, 2048))
                );
                Point.Builder pointBuilder = Point.measurement("commit")
                        .time(revCommit.getCommitTime(), TimeUnit.SECONDS)
                        .tag("branch", branch)
                        .addField("sha", revCommit.name())
                        .addField("shortsha", revCommit.name().substring(0, 10))
                        .addField("user", commiterName)
                        .addField("message", sanitizedMessage)
                        .addField("annotationHtml", annotationHtml);
                List<String> tags = tagsOfCommit(walk, revCommit);
                if (!tags.isEmpty()) {
                    pointBuilder.addField("tag", tags.get(0));
                }

                batchPoints.point(pointBuilder.build());
                revCommit = walk.parseCommit(revCommit.getParent(0));
            }
            influxDB.write(batchPoints);
        } catch (IOException | GitAPIException t) {
            throw new RuntimeException(t);
        }

    }

    private ObjectId resolve(String branch) throws IOException {
        RevWalk walk = new RevWalk(repo);
        try {
            ObjectId id = repo.resolve("origin/" + branch);
            if (id == null) {
                return walk.parseCommit(repo.resolve(branch));
            } else {
                return id;
            }
        } finally {
            walk.dispose();
        }
    }

    public List<String> tagsOfCommit(RevWalk walk, RevCommit revCommit) throws IOException, GitAPIException {
        List<Ref> tagList = new Git(repo).tagList().call();
        List<String> tags = new ArrayList<String>();
        for (Ref tag : tagList) {
            RevObject object = walk.parseAny(tag.getObjectId());
            if (object instanceof RevTag) {
                if (((RevTag) object).getObject().equals(revCommit)) {
                    tags.add(((RevTag) object).getTagName());
                }
            } else if (object instanceof RevCommit) {
                if (object.equals(revCommit)) {
                    tags.add(tag.getName());
                }

            } else {
                // invalid
            }
        }
        return tags;
    }
}
