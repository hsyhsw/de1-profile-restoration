package cc.adward.de1;


import android.content.ContentResolver;
import android.content.Context;
import android.support.v4.provider.DocumentFile;
import android.util.Log;
import android.util.Pair;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class BackupArchive {

    public static final String BACKUP_DIRECTORY_NAME = "_profile_backup";

    private ContentResolver contentResolver;
    private DocumentFile backupDir;
    private List<Backup> backups;

    public BackupArchive(Context ctx, DocumentFile installationDir) {
        this.contentResolver = ctx.getContentResolver();
        this.backupDir = installationDir.findFile(BACKUP_DIRECTORY_NAME);
        if (this.backupDir == null) {
            this.backupDir = installationDir.createDirectory(BACKUP_DIRECTORY_NAME);
        }
        listBackups();
    }

    public List<Backup> listBackups() {
        if (backups != null) {
            return backups;
        }

        backups = new ArrayList<>();
        DocumentFile[] files = backupDir.listFiles();
        Arrays.sort(files, (lhs, rhs) -> lhs.getName().compareTo(rhs.getName()));
        for (DocumentFile f : files) {
            if (f.getName().endsWith(Backup.BACKUP_EXT)) {
                backups.add(new Backup(f));
            }
        }
        return backups;
    }

    public void newBackup(DocumentFile profileDir) {
        DocumentFile[] files = profileDir.listFiles();
        String filename = Backup.makeBackupFilename(files.length);
        Backup b = new Backup(backupDir.createFile(Backup.BACKUP_MIME, filename));
        for (DocumentFile p : profileDir.listFiles()) {
            try (InputStream in = contentResolver.openInputStream(p.getUri())) {
                b.add(p.getName(), IOUtils.toByteArray(in));
            } catch (IOException e) {
                Log.w("backup", e);
            }
        }
        b.writeBackup(contentResolver);
        backups.add(b);
    }

    public void restoreFrom(Backup backup, DocumentFile profileDir) {
        backup.extractUnder(contentResolver, profileDir);
    }
}

class Backup {

    public static final String BACKUP_MIME = "application/de1_backup";
    public static final String BACKUP_EXT = ".pbackup";

    private Date timestamp;
    private DocumentFile backupFile;
    private Map<String, byte[]> profiles;
    private boolean readOnly;

    public Backup(DocumentFile file) {
        this.backupFile = file;
        this.timestamp = decodeBackupName(this.backupFile.getName()).first;
        this.profiles = new HashMap<>();
        this.readOnly = false;
    }

    @Override
    public String toString() {
        String time = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault(Locale.Category.FORMAT)).format(timestamp);
        int num = decodeBackupName(backupFile.getName()).second;
        return String.format("%s (%d profiles)", time, num);
    }

    private void readBackup(ContentResolver resolver) {
        readOnly = true;
        try (GZIPInputStream gzIn = new GZIPInputStream(resolver.openInputStream(backupFile.getUri()))) {
            profiles = new ObjectMapper().readValue(gzIn, new TypeReference<HashMap<String, byte[]>>() {
            });
        } catch (IOException e) {
            Log.w("backup", e);
        }
    }

    public void writeBackup(ContentResolver resolver) {
        try (GZIPOutputStream gzOut = new GZIPOutputStream(resolver.openOutputStream(backupFile.getUri(), "w"))) {
            new ObjectMapper().writeValue(gzOut, profiles);
        } catch (IOException e) {
            Log.w("backup", e);
        }
    }

    public static String makeBackupFilename(int profiles) {
        return String.format("%d_%d%s", new Date().getTime(), profiles, BACKUP_EXT);
    }

    private static Pair<Date, Integer> decodeBackupName(String name) {
        String[] splits = name.replace(BACKUP_EXT, " ").trim().split("_");
        long dateRaw = Long.parseLong(splits[0]);
        return Pair.create(new Date(dateRaw), Integer.parseInt(splits[1]));
    }

    public void add(String fileName, byte[] content) {
        if (readOnly) {
            throw new IllegalStateException("Modifying read-only backup!");
        }
        Log.v("backup", String.format("Profile added: %s (%d bytes)", fileName, content.length));
        profiles.put(fileName, content);
    }

    public void extractUnder(ContentResolver resolver, DocumentFile profileDir) {
        readBackup(resolver);
        for (Map.Entry<String, byte[]> e : profiles.entrySet()) {
            String filename = e.getKey();
            byte[] content = e.getValue();
            DocumentFile profileDest = profileDir.findFile(filename);
            if (profileDest == null) {
                profileDest = profileDir.createFile("application/profile", filename);
            }
            try (OutputStream out = resolver.openOutputStream(profileDest.getUri(), "wt")) {
                IOUtils.write(content, out);
            } catch (IOException x) {
                Log.w("backup", x);
            }
        }
    }
}
