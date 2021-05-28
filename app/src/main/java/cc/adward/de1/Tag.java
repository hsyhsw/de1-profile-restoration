package cc.adward.de1;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class Tag implements Serializable {
    private String sha;
    private String name;
    private long timestamp;
    private List<Profile> profiles;

    public Tag() {
        profiles = new ArrayList<>();
    }

    public Tag(String sha, String name, Date timestamp) {
        this.sha = sha;
        this.name = name;
        this.timestamp = timestamp.getTime();
        profiles = new ArrayList<>();
    }

    @Override
    public String toString() {
        return String.format("Tag{%s(%s), %s, %d profile(s)}", name, sha, new Date(timestamp), profiles.size());
    }

    public String getSha() {
        return sha;
    }

    public void setSha(String sha) {
        this.sha = sha;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @JsonIgnore
    public Date getDate() {
        return new Date(timestamp);
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public List<Profile> getProfiles() {
        return profiles;
    }

    public void setProfiles(List<Profile> profiles) {
        this.profiles = profiles;
    }
}
