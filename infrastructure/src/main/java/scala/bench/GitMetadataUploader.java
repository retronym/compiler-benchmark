package scala.bench;

import com.google.common.escape.Escaper;
import com.google.common.html.HtmlEscapers;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
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
        upload("2.13.x", "2.12.x");
        upload("2.12.x", "2.11.x");
        upload("2.11.x", "2.10.x");
        upload("2.10.x", "2.9.x");
        upload("2.9.x", "2.8.x");
    }

    public void upload(String branch, String prevBranch) {
        BatchPoints batchPoints = BatchPoints
                .database("scala_benchmark")
                .retentionPolicy("autogen")
                .consistency(InfluxDB.ConsistencyLevel.ALL)
                .build();
        try {
            ObjectId resolvedBranch = repo.resolve("origin/" + branch);
            ObjectId resolvedPrevBranch = repo.resolve("origin/" + prevBranch);
            RevWalk walk = (RevWalk) new Git(repo).log().add(resolvedBranch).not(resolvedPrevBranch).call();
            walk.sort(RevSort.TOPO);
            walk.setRevFilter(new FirstParentFilter());
            for (RevCommit revCommit : walk) {
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
            }
            influxDB.write(batchPoints);
        } catch (IOException | GitAPIException t) {
            throw new RuntimeException(t);
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

    class FirstParentFilter extends RevFilter {
        private Set<RevCommit> ignoreCommits = new HashSet<>();

        @Override
        public boolean include(RevWalk revWalk, RevCommit commit) throws IOException {
            for (int i = 0; i < commit.getParentCount() - 1; i++) {
                ignoreCommits.add(commit.getParent(i + 1));
            }
            boolean include = true;
            if (ignoreCommits.contains(commit)) {
                include = false;
                ignoreCommits.remove(commit);
            }
            return include;
        }

        @Override
        public RevFilter clone() {
            return new FirstParentFilter();
        }
    }
}
