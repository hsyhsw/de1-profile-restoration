package cc.adward.de1;

import java.io.InputStream;
import java.io.Serializable;
import java.util.Scanner;

public class Profile implements Serializable {
    private String sha;
    private String fileName;
    private String profileName;

    private String downloadLink;

    public Profile() {
        // intentionally empty
    }

    public Profile(String sha, String fileName, String profileName, String downloadLink) {
        this.sha = sha;
        this.fileName = fileName;
        this.profileName = profileName;
        this.downloadLink = downloadLink;
    }

    public static String resolveProfileName(InputStream in) {
        try (Scanner sc = new Scanner(in, "UTF-8")) {
            while (sc.hasNextLine()) {
                String line = sc.nextLine();
                if (line.startsWith("profile_title")) {
                    int firstSpace = line.indexOf(' ');
                    return line.substring(firstSpace)
                            .replace('{', ' ')
                            .replace('}', ' ').trim();
                }
            }
        }
        return "?";
    }

    public String getSha() {
        return sha;
    }

    public void setSha(String sha) {
        this.sha = sha;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getProfileName() {
        return profileName;
    }

    public void setProfileName(String profileName) {
        this.profileName = profileName;
    }

    public String getDownloadLink() {
        return downloadLink;
    }

    public void setDownloadLink(String downloadLink) {
        this.downloadLink = downloadLink;
    }
}
