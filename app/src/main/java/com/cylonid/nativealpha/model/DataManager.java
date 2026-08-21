package com.cylonid.nativealpha.model;

import android.content.SharedPreferences;
import android.net.Uri;
import android.view.Gravity;
import android.widget.Toast;

import com.cylonid.nativealpha.R;
import com.cylonid.nativealpha.util.App;
import com.cylonid.nativealpha.util.Const;
import com.cylonid.nativealpha.util.InvalidChecksumException;
import com.cylonid.nativealpha.util.ShortcutIconUtils;
import com.cylonid.nativealpha.util.Utility;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

import static android.content.Context.MODE_PRIVATE;


public class DataManager {

    // Corresponds to app release version
    private static final String SHARED_PREF_KEY = "WEBSITEDATA";
    private static final String GENERAL_INFO = "com.cylonid.nativealpha.GENERAL_INFO";
    public static final String EULA_ACCEPTED = "eulaAccepted";

    public static final String LAST_SHOWN_UPDATE = "lastShownUpdate";
    public static final String DATA_FORMAT = "dataFormat";

    private static final String SHARED_PREF_MAX_ID = "MAX_ID";

    private static final String SHARED_PREF_WEBAPP_DATA = "WEBSITEDATA";
    private static final String SHARED_PREF_GLOBAL_SETTINGS = "GLOBALSETTINGS";

    // 迁移兼容：旧版本把全局设置散列存放在 Cache/Cookies/... 键下，
    // 新版本统一存 JSON（GLOBALSETTINGS）。仅在检测到 legacy 键时触发迁移。
    private static final String SHARED_PREF_GLOB_CACHE = "Cache";
    private static final String SHARED_PREF_GLOB_COOKIE = "Cookies";
    private static final String SHARED_PREF_GLOB_2F_MULTITOUCH = "TwoFingerMultiTouch";
    private static final String SHARED_PREF_GLOB_MULTITOUCH_RELOAD = "ReloadMultiTouch";
    private static final String SHARED_PREF_GLOB_3F_MULTITOUCH = "ThreeFingerMultiTouch";
    private static final String SHARED_PREF_GLOB_PROGRESSBAR = "LoadProgressBarAlwaysShown";
    private static final String SHARED_PREF_GLOB_UI_THEME = "UITheme";
    private static final String SHARED_PREF_GLOBAL_SETTINGS_JSON = "globalSettingsStoredAsJson";

    private static final DataManager instance = new DataManager();
    private ArrayList<WebApp> websites;
    private int max_assigned_ID;
    private SharedPreferences appdata;

    private GlobalSettings settings;

    private DataManager()
    {
        websites = new ArrayList<>();
        max_assigned_ID = -1;
        settings = new GlobalSettings();
    }

    public static DataManager getInstance(){
        return instance;
    }

    public GlobalSettings getSettings() {
        return settings;
    }

    public void setSettings(GlobalSettings settings) {
        this.settings = settings;
        saveGlobalSettings();
    }

    public void saveWebAppData() {
        Utility.Assert(App.getAppContext() != null, "App.getAppContext() null before saving sharedpref");

        appdata = App.getAppContext().getSharedPreferences(SHARED_PREF_KEY, MODE_PRIVATE);
        SharedPreferences.Editor editor = appdata.edit();
        Gson gson = new Gson();
        String json = gson.toJson(websites);
        editor.putString(SHARED_PREF_WEBAPP_DATA, json);
        editor.putInt(SHARED_PREF_MAX_ID, max_assigned_ID);
        editor.apply();
    }



    public boolean getEulaData() {
        return getGeneralInfo().getBoolean(EULA_ACCEPTED, false);
    }

    public int getLastShownUpdate() {
        return getGeneralInfo().getInt(LAST_SHOWN_UPDATE, 0);
    }

    public void setEulaData(boolean newValue) {
        getGeneralInfo().edit().putBoolean(EULA_ACCEPTED, newValue).apply();
    }

    public void setLastShownUpdate(int newValue) {
        getGeneralInfo().edit().putInt(LAST_SHOWN_UPDATE, newValue).apply();
    }

    private SharedPreferences getGeneralInfo() {
        Utility.Assert(App.getAppContext() != null, "App.getAppContext() null before saving sharedpref");
        return App.getAppContext().getSharedPreferences(GENERAL_INFO, MODE_PRIVATE);

    }

    private void checkIfWebAppIdsCollide(ArrayList<WebApp> oldWebApps, ArrayList<WebApp> newWebApps) {
        int end = Math.min(oldWebApps.size(), newWebApps.size());
        ArrayList<Integer> shortcuts_to_be_removed = new ArrayList<>();

        for (int i = 0; i < end; i++) {
            if (oldWebApps.get(i) != null && newWebApps.get(i) != null) {
                if (!oldWebApps.get(i).getBaseUrl().equals(newWebApps.get(i).getBaseUrl())) {
                    shortcuts_to_be_removed.add(newWebApps.get(i).getID());
                }
            }
        }

        ShortcutIconUtils.deleteShortcuts(shortcuts_to_be_removed, App.getAppContext());

    }



    /** 是否已从 SharedPreferences 加载过数据（幂等优化：启动路径多次调用不再重复 Gson 解析） */
    private boolean dataLoaded = false;

    public void loadAppData() {
        loadAppData(false);
    }

    /**
     * 加载应用数据。
     * @param force true 时强制重新解析（数据变更后调用，如设置保存/导入备份）
     */
    public void loadAppData(boolean force) {
        if (dataLoaded && !force) return;

        Utility.Assert(App.getAppContext() != null, "App.getAppContext() null before loading sharedpref");

        appdata = App.getAppContext().getSharedPreferences(SHARED_PREF_KEY, MODE_PRIVATE);
        //Webapp data（D14：不做旧版兼容，直接默认 Gson 反序列化）
        if (appdata.contains(SHARED_PREF_WEBAPP_DATA)) {
            Gson gson = new Gson();
            String json = appdata.getString(SHARED_PREF_WEBAPP_DATA, "");
            ArrayList<WebApp> new_websites = gson.fromJson(json, new TypeToken<ArrayList<WebApp>>() {}.getType());
            if (new_websites != null) {
                checkIfWebAppIdsCollide(websites, new_websites);
                websites = new_websites;
            }
        }

        max_assigned_ID = appdata.getInt(SHARED_PREF_MAX_ID, max_assigned_ID);

        if (appdata.getBoolean(SHARED_PREF_GLOBAL_SETTINGS_JSON, false)) {
            loadGlobalSettingsLegacy();
        }
        //Global settings（D14：不做旧版兼容，直接默认 Gson 反序列化）
        if (appdata.contains(SHARED_PREF_GLOBAL_SETTINGS)) {
            Gson gson = new Gson();
            String json = appdata.getString(SHARED_PREF_GLOBAL_SETTINGS, "");
            GlobalSettings loaded = gson.fromJson(json, GlobalSettings.class);
            if (loaded != null) {
                // 空值防护：JSON 缺失 globalWebApp 字段时 Gson 返回 null，补默认值防 NPE
                if (loaded.getGlobalWebApp() == null) {
                    loaded.setGlobalWebApp(new WebApp("about:blank", Integer.MAX_VALUE));
                }
                settings = loaded;
                assertGlobalWebappData();
            }
        }

        dataLoaded = true;
    }

    /** 迁移旧版散列全局设置（仅在检测到 globalSettingsStoredAsJson 键时触发） */
    public void loadGlobalSettingsLegacy() {
        settings.setClearCache(appdata.getBoolean(SHARED_PREF_GLOB_CACHE, false));
        settings.setClearCookies(appdata.getBoolean(SHARED_PREF_GLOB_COOKIE, false));
        settings.setTwoFingerMultitouch(appdata.getBoolean(SHARED_PREF_GLOB_2F_MULTITOUCH, true));
        settings.setMultitouchReload(appdata.getBoolean(SHARED_PREF_GLOB_MULTITOUCH_RELOAD, true));
        settings.setThreeFingerMultitouch(appdata.getBoolean(SHARED_PREF_GLOB_3F_MULTITOUCH, false));
        settings.setShowProgressbar(appdata.getBoolean(SHARED_PREF_GLOB_PROGRESSBAR, false));
        settings.setThemeId(appdata.getInt(SHARED_PREF_GLOB_UI_THEME, 0));
    }

    public void saveGlobalSettings() {
        Utility.Assert(App.getAppContext() != null, "App.getAppContext() null before saving appdata to sharedpref");

        appdata = App.getAppContext().getSharedPreferences(SHARED_PREF_KEY, MODE_PRIVATE);
        SharedPreferences.Editor editor = appdata.edit();

        Gson gson = new Gson();
        String json = gson.toJson(settings);
        editor.putString(SHARED_PREF_GLOBAL_SETTINGS, json);
        editor.putBoolean(SHARED_PREF_GLOBAL_SETTINGS_JSON, true);
        editor.apply();
    }

    public void addWebsite(WebApp new_site) {
            websites.add(new_site);
            saveWebAppData();
    }

    public int getIncrementedID() {
        return getWebsites().size();
    }

    public int getIncrementedOrder() {
        return getActiveWebsitesCount() + 1;
    }

    public ArrayList<WebApp> getWebsites() {
        Utility.Assert(websites != null, "Websites not loaded");
        return websites;
    }

    public ArrayList<WebApp> getActiveWebsites() {
        ArrayList<WebApp> active_webapps = new ArrayList<>();

        for (WebApp webapp : websites) {
            if (webapp.isActiveEntry())
                active_webapps.add(webapp);
        }
        active_webapps.sort(Comparator.comparingInt(WebApp::getOrder));
        return active_webapps;
    }


    public WebApp getWebApp(int i) {
        WebApp webAppIgnoringGlobalOverride = getWebAppIgnoringGlobalOverride(i, false);
        return webAppIgnoringGlobalOverride;
    }

    public WebApp getWebAppIgnoringGlobalOverride(int i, boolean ignoreOverride) {
        loadAppData();
        try {
            WebApp webApp = websites.get(i);
            if (!webApp.isOverrideGlobalSettings() && !ignoreOverride) {
                // 深拷贝后合并全局设置，避免污染原对象（原对象保留自身设置，供"应用设置为主"使用）
                WebApp merged = new WebApp(webApp.getBaseUrl(), webApp.getID(), webApp.getOrder());
                merged.setTitle(webApp.getTitle());
                merged.setDisplayName(webApp.getDisplayName());
                merged.copySettings(settings.getGlobalWebApp());
                // 外观设置（字体/页面缩放）不参与全局合并：始终用 WebApp 自身的值
                merged.setTextZoom(webApp.getTextZoom());
                merged.setPageZoom(webApp.getPageZoom());
                merged.setOverrideGlobalSettings(false);
                // 统计字段不参与 copySettings 合并：从原对象单独复制（防统计丢失）
                merged.setStatLaunches(webApp.getStatLaunches());
                merged.setStatLoadTimeSum(webApp.getStatLoadTimeSum());
                merged.setStatLoadTimeCount(webApp.getStatLoadTimeCount());
                merged.setStatMaxLoadTime(webApp.getStatMaxLoadTime());
                merged.setStatCacheHttpBytes(webApp.getStatCacheHttpBytes());
                merged.setStatCacheStoreBytes(webApp.getStatCacheStoreBytes());
                merged.setStatErrors(webApp.getStatErrors());
                merged.setStatLastError(webApp.getStatLastError());
                merged.setStatFirstLoadedAt(webApp.getStatFirstLoadedAt());
                merged.setStatLastUsedAt(webApp.getStatLastUsedAt());
                // 加载耗时明细 + 发送计数（新统计字段，合并时保留）
                if (webApp.getStatLoadTimes() != null) {
                    merged.setStatLoadTimes(new java.util.ArrayList<>(webApp.getStatLoadTimes()));
                }
                if (webApp.getKeyShortcutSendCounts() != null) {
                    merged.setKeyShortcutSendCounts(new java.util.HashMap<>(webApp.getKeyShortcutSendCounts()));
                }
                // 组合快捷键不参与 copySettings 合并：从原对象复制（每站独立）
                if (webApp.getKeyShortcuts() != null) {
                    merged.setKeyShortcuts(new java.util.ArrayList<>(webApp.getKeyShortcuts()));
                }
                return merged;
            }
            return websites.get(i);
        }
        catch (IndexOutOfBoundsException e) {
            Toast toast = Toast.makeText(App.getAppContext(), App.getAppContext().getString(R.string.webapp_not_found), Toast.LENGTH_LONG);
            toast.setGravity(Gravity.TOP, 0, 100);
            toast.show();
        }
        return null;
    }

    public void replaceWebApp(WebApp webapp) {
        int index = webapp.getID();
        websites.set(index, webapp);
        saveWebAppData();
    }

    public int getActiveWebsitesCount() {
        int c = 0;
        for (WebApp webapp : websites) {
            if (webapp.isActiveEntry())
                c += 1;
        }
        return c;
    }


    /** 备份格式版本（D15：版本化 JSON，不兼容旧版） */
    private static final int BACKUP_FORMAT_VERSION = 2;

    /** 导出：版本化 JSON（websites + settings + 校验和） */
    public boolean saveSharedPreferencesToFile(Uri uri) {
        boolean result = false;
        try (FileOutputStream fos = (FileOutputStream) App.getAppContext().getContentResolver().openOutputStream(uri)) {
            appdata = App.getAppContext().getSharedPreferences(SHARED_PREF_KEY, MODE_PRIVATE);
            loadAppData();

            // 备份内容：版本 + 导出时间 + Web Apps + 全局设置
            Map<String, Object> backup = new TreeMap<>();
            backup.put("version", BACKUP_FORMAT_VERSION);
            backup.put("exportedAt", System.currentTimeMillis());
            backup.put("websites", websites);
            backup.put("settings", settings);

            String json = new Gson().toJson(backup);
            String checksum = sha256Hex(json);
            String payload = "{\"checksum\":\"" + checksum + "\",\"data\":" + json + "}";

            fos.write(payload.getBytes(StandardCharsets.UTF_8));
            result = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    /** SHA-256 摘要（十六进制）。备份完整性校验用。 */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 导入：校验和验证 + 版本化 JSON 解析（D15：不兼容旧版格式） */
    public boolean loadSharedPreferencesFromFile(Uri uri) {
        boolean result = false;
        try (FileInputStream fis = (FileInputStream) App.getAppContext().getContentResolver().openInputStream(uri)) {
            byte[] bytes = new byte[fis.available()];
            int read = fis.read(bytes);
            if (read <= 0) return false;
            String payload = new String(bytes, StandardCharsets.UTF_8);

            // 解析外层 {checksum, data}
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
            if (root == null || !root.has("checksum") || !root.has("data")) {
                return false; // 非本应用备份格式
            }
            String checksum = root.get("checksum").getAsString();
            String data = root.get("data").toString();

            // 校验和验证（防篡改/损坏）
            String newChecksum = sha256Hex(data);
            if (!checksum.equals(newChecksum)) {
                throw new InvalidChecksumException("Checksums between backup and restored settings do not match.");
            }

            // 解析数据体
            com.google.gson.JsonObject dataObj = com.google.gson.JsonParser.parseString(data).getAsJsonObject();
            if (dataObj == null || !dataObj.has("version") || !dataObj.has("websites") || !dataObj.has("settings")) {
                return false; // 数据体不完整
            }
            int version = dataObj.get("version").getAsInt();
            if (version != BACKUP_FORMAT_VERSION) {
                throw new InvalidChecksumException("Unsupported backup format version: " + version);
            }

            Gson gson = new Gson();
            ArrayList<WebApp> loadedWebsites = gson.fromJson(
                    dataObj.get("websites"), new TypeToken<ArrayList<WebApp>>() {}.getType());
            GlobalSettings loadedSettings = gson.fromJson(dataObj.get("settings"), GlobalSettings.class);

            if (loadedWebsites != null) {
                websites = loadedWebsites;
                saveWebAppData();
            }
            if (loadedSettings != null) {
                if (loadedSettings.getGlobalWebApp() == null) {
                    loadedSettings.setGlobalWebApp(new WebApp("about:blank", Integer.MAX_VALUE));
                }
                settings = loadedSettings;
                saveGlobalSettings();
            }
            result = true;

        } catch (InvalidChecksumException | IOException | RuntimeException e) {
            // RuntimeException 覆盖 JsonSyntaxException（用户选了非备份文件）
            e.printStackTrace();
        }
        return result;
    }

    public WebApp getSuccessor(int i) {
        int INVALID = websites.size();
        int neighbor = i;
        do {
            neighbor = neighbor + 1;
            if (neighbor == INVALID)
                neighbor = 0;
        }
        while (!websites.get(neighbor).isActiveEntry());
        return websites.get(neighbor);

    }
    public WebApp getPredecessor(int i) {
        int INVALID = -1;
        int neighbor = i;
        do {
            neighbor = neighbor - 1;
            if (neighbor == INVALID)
                neighbor = websites.size() - 1;
        }
        while (!websites.get(neighbor).isActiveEntry());
        return websites.get(neighbor);
    }

    private void assertGlobalWebappData() {
        boolean override = settings.getGlobalWebApp().isOverrideGlobalSettings();
        int container = settings.getGlobalWebApp().getContainerId();
        if(!override || container != Const.NO_CONTAINER) {
            settings.getGlobalWebApp().setOverrideGlobalSettings(true);
            settings.getGlobalWebApp().setContainerId(Const.NO_CONTAINER);
            this.saveGlobalSettings();
        }
    }


}

