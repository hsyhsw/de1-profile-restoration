package cc.adward.de1;

import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.provider.DocumentFile;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;

public class MainActivity extends AppCompatActivity
        implements View.OnClickListener, AdapterView.OnItemSelectedListener {

    private static final int DE1_DIRECTORY_OPEN_REQUEST = 1;

    public static final String PROFILE_DIR_NAME = "profiles";

    private ContentResolver contentResolver;
    private DocumentFile de1Installation;
    private DocumentFile profileDir;

    private ProfileLibrary pl;
    private BackupArchive backupArchive;

    private ActionBar topToolbar;

    private TextView de1Path;

    private Spinner tagSelector;
    private List<Tag> tags;

    private ListView tagProfileList;
    private List<Map<String, String>> tagProfiles;

    private Button selectAllButton;
    private Button deselectAllButton;
    private Button restoreButton;

    private Spinner backupSelector;
    private Button backupButton;
    private Button restoreFromBackupButton;

    private ListView installedProfileList;
    private List<Map<String, String>> installedProfiles; // (name, filename)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        topToolbar = getSupportActionBar();
        // topToolbar.view
        // TODO: self update profile library from github HEAD commit

        // top: de1 installation picker
        de1Path = findViewById(R.id.de1_dir_path);
        de1Path.setOnClickListener(this);

        // left: tags, available profiles
        tagSelector = findViewById(R.id.tag_selector);
        tagProfileList = findViewById(R.id.tag_profiles);

        try (InputStream in = getResources().openRawResource(R.raw.profiles_json_gz)) {
            pl = ProfileLibrary.load(in);
        } catch (IOException e) {
            Log.w("ProfileLibrary", e);
        }
        tags = pl.tagsAsList();
        DateFormat df = new SimpleDateFormat("yyyy/MM/dd", Locale.getDefault(Locale.Category.FORMAT));
        List<String> tagLabels = tags.stream()
                .map(t -> String.format("%s at %s, %d profile(s)", t.getName(), df.format(t.getDate()), t.getProfiles().size()))
                .collect(Collectors.toList());
        tagSelector.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, tagLabels));
        tagSelector.setOnItemSelectedListener(this);

        // bottom buttons
        findViewById(R.id.btn_select_all).setOnClickListener(this);
        findViewById(R.id.btn_deselect_all).setOnClickListener(this);
        restoreButton = findViewById(R.id.btn_restore);
        backupButton = findViewById(R.id.btn_backup_all);
        restoreFromBackupButton = findViewById(R.id.btn_restore_from);
        restoreButton.setOnClickListener(this);
        backupButton.setOnClickListener(this);
        restoreFromBackupButton.setOnClickListener(this);

        // right: currently installed profiles
        installedProfiles = new ArrayList<>();
        installedProfileList = findViewById(R.id.list_installed_profiles);
        installedProfileList.setAdapter(new SimpleAdapter(this, installedProfiles,
                android.R.layout.simple_list_item_2,
                new String[]{"profileName", "fileName"},
                new int[]{android.R.id.text1, android.R.id.text2}));

        contentResolver = getContentResolver();

        tagProfiles = new ArrayList<>();
        tagProfileList.setAdapter(new SimpleAdapter(this, tagProfiles,
                android.R.layout.simple_list_item_activated_2,
                new String[]{"profileName", "fileName"},
                new int[]{android.R.id.text1, android.R.id.text2}));

        // backup selection spinner
        backupSelector = findViewById(R.id.backup_selector);
    }

    private void requestDe1Installation() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, DE1_DIRECTORY_OPEN_REQUEST);
    }

    private void populateInstalledProfiles() {
        Log.i("listing-profiles", de1Installation.getUri().toString());
        installedProfiles.clear();
        for (DocumentFile f : profileDir.listFiles()) {
            try (InputStream in = contentResolver.openInputStream(f.getUri())) {
                String profileName = Profile.resolveProfileName(in);
                Map<String, String> item = new HashMap<>();
                item.put("profileName", profileName);
                item.put("fileName", "File name: " + f.getName());
                installedProfiles.add(item);
            } catch (IOException e) {
                Log.w("de1-profiles", e);
            }
        }
        Log.i("listing-profiles", String.format("%d profiles available", installedProfiles.size()));
        ((BaseAdapter) installedProfileList.getAdapter()).notifyDataSetChanged();
    }

    private void writeProfile(String filename, InputStream content) {
        DocumentFile profileDest = profileDir.findFile(filename);
        if (profileDest == null) {
            profileDest = profileDir.createFile("application/profile", filename);
        }
        try (OutputStream out = contentResolver.openOutputStream(profileDest.getUri(), "wt")) {
            IOUtils.copy(content, out);
        } catch (IOException x) {
            Log.w("profile-writing", x);
        }
    }

    private void restoreFromTagProfile(Profile p) {
        try (InputStream in = pl.getInputStream(p)) {
            writeProfile(p.getFileName(), in);
        } catch (IOException e) {
            Log.w("profile-restore", e);
        }
    }

    private void handleDe1InstallationResult(Uri installationUri) {
        de1Installation = DocumentFile.fromTreeUri(this, installationUri);
        if (de1Installation.isFile() || de1Installation.findFile("version.tcl") == null) {
            Toast.makeText(this, "Pick correct installation folder!", Toast.LENGTH_SHORT).show();
            requestDe1Installation();
        } else {
            de1Path.setEnabled(false);
            // initialize backup archive
            profileDir = de1Installation.findFile(PROFILE_DIR_NAME);
            backupArchive = new BackupArchive(this, de1Installation);
            if (backupArchive.listBackups().size() == 0) {
                backupArchive.newBackup(profileDir);
            }
            backupSelector.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, backupArchive.listBackups()));

            // extract version
            Uri versionFile = de1Installation.findFile("version.tcl").getUri();
            try (Scanner sc = new Scanner(contentResolver.openInputStream(versionFile))) {
                String de1Version = sc.nextLine().split("\\s")[3];
                de1Path.setText("DE1 v" + de1Version);
                // select best matching tag
                for (int i = 0; i < tagSelector.getAdapter().getCount(); ++i) {
                    String str = (String) tagSelector.getAdapter().getItem(i);
                    // cute hack to autoselecting installed version
                    if (str.startsWith(de1Version + " at") || str.startsWith("v" + de1Version + " at")) {
                        tagSelector.setSelection(i);
                        break;
                    }
                }
            } catch (IOException e) {
                Log.w("de1-version", e);
            }
            populateInstalledProfiles();
            restoreButton.setEnabled(true);
            backupButton.setEnabled(true);
            restoreFromBackupButton.setEnabled(true);
        }
    }

    private void selectAllProfile() {
        for (int i = 0; i < tagProfiles.size(); ++i) {
            tagProfileList.setItemChecked(i, true);
        }
    }

    private void deselectAllProfile() {
        for (int i = 0; i < tagProfiles.size(); ++i) {
            tagProfileList.setItemChecked(i, false);
        }
    }

    private void handleRestore() {
        List<Profile> profiles = tags.get(tagSelector.getSelectedItemPosition()).getProfiles();
        SparseBooleanArray selected = tagProfileList.getCheckedItemPositions();
        ProgressDialog progress = ProgressDialog.show(this, "Restoring", "Restoring profiles...");
        AsyncTask.execute(() -> {
            for (int i = 0; i < selected.size(); ++i) {
                int idx = selected.keyAt(i);
                if (selected.valueAt(i)) {
                    Profile p = profiles.get(idx);
                    Log.v("profile-restore", p.getProfileName());
                    restoreFromTagProfile(p);
                }
            }
            runOnUiThread(this::populateInstalledProfiles);
            progress.dismiss();
        });

    }

    private void handleBackupAll() {
        ProgressDialog progress = ProgressDialog.show(this, "Backup", "Backing up profiles...");
        AsyncTask.execute(() -> {
            backupArchive.newBackup(profileDir);
            runOnUiThread(() -> ((BaseAdapter) backupSelector.getAdapter()).notifyDataSetChanged());
            progress.dismiss();
        });
    }

    private void handleRestoreFromBackup() {
        ProgressDialog progress = ProgressDialog.show(this, "Restoring", "Restoring profiles from a backup...");
        AsyncTask.execute(() -> {
            Backup b = (Backup) backupSelector.getSelectedItem();
            backupArchive.restoreFrom(b, profileDir);
            runOnUiThread(this::populateInstalledProfiles);
            progress.dismiss();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        if (requestCode == DE1_DIRECTORY_OPEN_REQUEST && resultCode == RESULT_OK) {
            if (result != null) {
                handleDe1InstallationResult(result.getData());
            }
        }
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.de1_dir_path) {
            requestDe1Installation();
        }
        if (v.getId() == R.id.btn_select_all) {
            selectAllProfile();
        }
        if (v.getId() == R.id.btn_deselect_all) {
            deselectAllProfile();
        }
        if (v.getId() == R.id.btn_restore) {
            handleRestore();
        }
        if (v.getId() == R.id.btn_backup_all) {
            handleBackupAll();
        }
        if (v.getId() == R.id.btn_restore_from) {
            handleRestoreFromBackup();
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (parent.getId() == tagSelector.getId()) {
            deselectAllProfile();

            Tag selected = tags.get(position);
            Log.i("tag-selection", selected.toString());
            tagProfiles.clear();
            int idx = 0;
            for (Profile p : selected.getProfiles()) {
                Map<String, String> item = new HashMap<>();
                item.put("profileName", p.getProfileName());
                item.put("fileName", "File name: " + p.getFileName());
                tagProfiles.add(item);
            }
            ((BaseAdapter) tagProfileList.getAdapter()).notifyDataSetChanged();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (de1Installation != null) {
            populateInstalledProfiles();
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        // intentionally empty
    }

}