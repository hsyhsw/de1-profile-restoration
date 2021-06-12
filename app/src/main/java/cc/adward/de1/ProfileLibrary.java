package cc.adward.de1;

import android.os.Build;
import android.support.annotation.RequiresApi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
    private Long version; // yyyyMMddHHmm
    @JsonProperty
    private Map<String, Tag> tags; // {tag_sha : tag}
    @JsonProperty
    private Map<String, byte[]> contentCache; // {profile_sha : content_bytes}

    public ProfileLibrary() {
        this.version = Long.parseLong(new SimpleDateFormat("yyyyMMddHHmm").format(new Date()));
        this.tags = new HashMap<>();
        this.contentCache = new HashMap<>();
    }

    public ProfileLibrary(Long version, Map<String, Tag> tags, Map<String, byte[]> contentCache) {
        this.version = version;
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

    public Long getVersion() {
        return version;
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
        if (newTags.size() != 0) {
            version = Long.parseLong(new SimpleDateFormat("yyyyMMddHHmm").format(new Date()));
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

    public static byte[] fetchLatestLibRelease() throws IOException, JSONException {
        final String releaseUrl = "https://api.github.com/repos/hsyhsw/de1-profile-restoration/releases";

        // list pre-release and find the latest
        HttpClient http = HttpClientBuilder.create().build();
        HttpGet getReq = new HttpGet(releaseUrl);
        HttpResponse res = http.execute(getReq);
        long latestVersion = 0;
        String downloadUrl = null;
        if (res.getStatusLine().getStatusCode() == 200) {
            try (InputStream in = res.getEntity().getContent()) {
                String jsonStr = IOUtils.toString(in, "UTF-8");
                JSONArray rels = new JSONArray(jsonStr);
                for (int i = 0; i < rels.length(); ++i) {
                    JSONObject o = rels.getJSONObject(i);
                    if (o.getBoolean("prerelease")) {
                        long version = Long.parseLong(o.getString("tag_name"));
                        String url = o.getJSONArray("assets").getJSONObject(0).getString("browser_download_url");
                        if (version > latestVersion) {
                            latestVersion = version;
                            downloadUrl = url;
                        }
                    }
                }
            }
        }

        // download latest and return it
        if (downloadUrl != null) {
            res = http.execute(new HttpGet(downloadUrl));
            if (res.getStatusLine().getStatusCode() == 200) {
                try (InputStream in = res.getEntity().getContent()) {
                    return IOUtils.toByteArray(in);
                }
            }
        }

        return null;
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
