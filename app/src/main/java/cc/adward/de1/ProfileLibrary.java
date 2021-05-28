package cc.adward.de1;

import android.os.Build;
import android.support.annotation.RequiresApi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.io.IOUtils;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTag;
import org.kohsuke.github.GitHub;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import cz.msebera.android.httpclient.HttpResponse;
import cz.msebera.android.httpclient.client.HttpClient;
import cz.msebera.android.httpclient.client.methods.HttpGet;
import cz.msebera.android.httpclient.impl.client.HttpClientBuilder;


public class ProfileLibrary implements Serializable {

    public static final String PROFILE_LIB_FILE = "profiles.json.gz";
    public static final String PROFILE_LIB_FILE_ID = PROFILE_LIB_FILE.replaceAll("\\.", "_");

    @JsonIgnore
    private static final Logger logger = Logger.getGlobal();

    @JsonIgnore
    private GHRepository de1Repo;
    @JsonIgnore
    private HttpClient httpClient;

    @JsonProperty
    private Map<String, Tag> tags; // {tag_sha : tag}
    @JsonProperty
    private Map<String, byte[]> contentCache; // {profile_sha : content_bytes}

    public ProfileLibrary() {
        this.tags = new HashMap<>();
        this.contentCache = new HashMap<>();
    }

    public ProfileLibrary(Map<String, Tag> tags, Map<String, byte[]> contentCache) {
        this.tags = tags;
        this.contentCache = contentCache;
    }

    public void init(String apiKey) {
        try {
            GitHub gh = GitHub.connectUsingOAuth(apiKey);
            de1Repo = gh.getRepository("decentespresso/de1app");
            httpClient = HttpClientBuilder.create().build();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to initialize profile library!", e);
            de1Repo = null;
            httpClient = null;
        }
    }

    private Map<String, Tag> fetchNewTags() throws IOException {
        logger.info("fetching tags...");
        Map<String, Tag> newTags = new HashMap<>();
        List<GHTag> absentTags = de1Repo.listTags().toList().stream()
                .filter(t -> !this.tags.containsKey(t.getCommit().getSHA1()))
                .collect(Collectors.toList());
        for (GHTag t : absentTags) {
            try {
                de1Repo.getDirectoryContent("de1plus/profiles", t.getCommit().getSHA1());
                newTags.put(t.getCommit().getSHA1(), new Tag(t.getCommit().getSHA1(), t.getName(), t.getCommit().getCommitDate()));
            } catch (Exception e) {
                logger.warning(String.format("malformed tag: %s(%s)", t.getName(), t.getCommit().getSHA1()));
            }
        }
        logger.info(String.format("%d new tags available", newTags.size()));
        return newTags;
    }

    private List<Profile> populateProfiles(String tagSha) throws IOException {
        List<Profile> profilesEmpty = new ArrayList<>();
        for (GHContent c : de1Repo.getDirectoryContent("de1plus/profiles", tagSha)) {
            profilesEmpty.add(new Profile(c.getSha(), c.getName(), "", c.getDownloadUrl()));
        }
        return profilesEmpty;
    }

    public void update() throws IOException {
        if (de1Repo == null) {
            throw new IllegalStateException("DE1 repository is not properly initialized!");
        }

        logger.info(String.format("%d tags exists", this.tags.size()));
        Map<String, Tag> newTags = fetchNewTags();
        for (String sha : newTags.keySet()) {
            Tag t = newTags.get(sha);
            logger.info(String.format("updating %s", t));
            for (Profile p : populateProfiles(sha)) {
                // fill in profile name
                if (!contentCache.containsKey(p.getSha())) {
                    // update file sha -> content cache
                    HttpGet getReq = new HttpGet(p.getDownloadLink());
                    HttpResponse res = httpClient.execute(getReq);
                    if (res.getStatusLine().getStatusCode() == 200) {
                        try (InputStream in = res.getEntity().getContent()) {
                            contentCache.put(p.getSha(), IOUtils.toByteArray(in));
                        }

                    } else {
                        logger.warning("Profile download failed: " + res.getStatusLine().getReasonPhrase());
                    }
                }
                byte[] profileContent = contentCache.get(p.getSha());
                String profileName = Profile.resolveProfileName(new ByteArrayInputStream(profileContent));
                p.setProfileName(profileName);
                logger.info(String.format("%s: %s -> %s", t.getName(), p.getFileName(), p.getProfileName()));
                t.getProfiles().add(p);
            }
        }
        tags.putAll(newTags);
    }

    public List<Tag> tagsAsList() {
        return tags.values().stream()
                .sorted((lhs, rhs) -> Long.compare(rhs.getTimestamp(), lhs.getTimestamp()))
                .collect(Collectors.toList());
    }

    @JsonIgnore
    public InputStream getInputStream(Profile p) {
        return new ByteArrayInputStream(contentCache.get(p.getSha()));
    }

    public static ProfileLibrary load(InputStream in) throws IOException {
        try (GZIPInputStream zIn = new GZIPInputStream(in)) {
            return new ObjectMapper().readValue(zIn, ProfileLibrary.class);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static ProfileLibrary load(String path) throws IOException {
        if (path == null) {
            path = PROFILE_LIB_FILE;
        }

        if (!Files.exists(Paths.get(path))) {
            new ProfileLibrary().save(path);
        }
        try (InputStream in = Files.newInputStream(Paths.get(path))) {
            return load(in);
        }
    }

    public void save(OutputStream out) throws IOException {
        try (GZIPOutputStream zOut = new GZIPOutputStream(out)) {
            new ObjectMapper().writeValue(zOut, this);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void save(String path) throws IOException {
        if (path == null) {
            path = PROFILE_LIB_FILE;
        }
        try (OutputStream out = Files.newOutputStream(Paths.get(path))) {
            save(out);
        }
    }
}

class LibraryUpdater {
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void main(String[] args) throws IOException {
        ProfileLibrary l = ProfileLibrary.load((String) null);
        l.init(args[0]); // args[0]: github api key enabled for accessing public repos
        l.update();
        l.save((String) null);
        l.tagsAsList().forEach(System.out::println);

        Path targetPath = Paths.get("app/src/main/res/raw/" + ProfileLibrary.PROFILE_LIB_FILE_ID);
        Files.copy(Paths.get(ProfileLibrary.PROFILE_LIB_FILE), targetPath, StandardCopyOption.REPLACE_EXISTING);
    }
}
