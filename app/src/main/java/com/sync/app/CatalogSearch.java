package com.sync.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 곡 검색 정확도 보강 — 무료 음원 카탈로그(iTunes Search, Deezer)와 YouTube Music 결과를 교차 검증한다.
 * 앨범/싱글/EP 에 실제로 등록된 곡만 남기고, 카탈로그에서 확인된 곡을 위로 올린다.
 * 카탈로그 요청은 YTM 요청과 동시에 진행되고(2.5초 제한) 실패해도 검색은 YTM 결과만으로 정상 동작한다.
 */
final class CatalogSearch {
    static final class Hit { String title = "", artist = "", album = ""; int dur; }

    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static volatile OkHttpClient fast;

    private static OkHttpClient client(OkHttpClient base) {
        OkHttpClient c = fast;
        if (c == null) {
            c = base.newBuilder().callTimeout(2500, TimeUnit.MILLISECONDS)
                    .connectTimeout(2000, TimeUnit.MILLISECONDS).build();
            fast = c;
        }
        return c;
    }

    static Future<List<Hit>> start(final OkHttpClient base, final String q) {
        return POOL.submit(new Callable<List<Hit>>() {
            @Override public List<Hit> call() {
                final OkHttpClient c = client(base);
                Future<List<Hit>> it = POOL.submit(new Callable<List<Hit>>() {
                    @Override public List<Hit> call() { return itunes(c, q); }
                });
                List<Hit> out = new ArrayList<>(deezer(c, q));
                try { out.addAll(0, it.get(2500, TimeUnit.MILLISECONDS)); } catch (Exception ignored) {}
                // iTunes 가 앞, Deezer 가 뒤. 같은 곡은 한 번만.
                List<Hit> r = new ArrayList<>();
                HashSet<String> seen = new HashSet<>();
                for (Hit h : out) if (seen.add(norm(h.title) + "|" + norm(h.artist))) r.add(h);
                return r;
            }
        });
    }

    private static String get(OkHttpClient c, String url) throws Exception {
        Request req = new Request.Builder().url(url).addHeader("User-Agent", "Mozilla/5.0 SYNC").build();
        try (Response resp = c.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) throw new java.io.IOException("HTTP " + resp.code());
            return resp.body().string();
        }
    }

    private static List<Hit> itunes(OkHttpClient c, String q) {
        List<Hit> l = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(get(c, "https://itunes.apple.com/search?media=music&entity=song&limit=25&country=KR&term="
                    + URLEncoder.encode(q, "UTF-8"))).optJSONArray("results");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Hit h = new Hit();
                h.title = o.optString("trackName"); h.artist = o.optString("artistName");
                h.album = o.optString("collectionName"); h.dur = o.optInt("trackTimeMillis") / 1000;
                if (!h.title.isEmpty()) l.add(h);
            }
        } catch (Exception ignored) {}
        return l;
    }

    private static List<Hit> deezer(OkHttpClient c, String q) {
        List<Hit> l = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(get(c, "https://api.deezer.com/search/track?limit=25&q="
                    + URLEncoder.encode(q, "UTF-8"))).optJSONArray("data");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Hit h = new Hit();
                h.title = o.optString("title");
                JSONObject ar = o.optJSONObject("artist"), al = o.optJSONObject("album");
                h.artist = ar == null ? "" : ar.optString("name");
                h.album = al == null ? "" : al.optString("title");
                h.dur = o.optInt("duration");
                if (!h.title.isEmpty()) l.add(h);
            }
        } catch (Exception ignored) {}
        return l;
    }

    /** 괄호/리마스터 꼬리표 제거 + 문자·숫자만 남긴 비교용 문자열 */
    static String norm(String s) {
        if (s == null) return "";
        String l = s.toLowerCase(Locale.ROOT);
        String t = l.replaceAll("[(\\[（].*?[)\\]）]", " ").replaceAll("\\s-\\s.*$", " ").replaceAll("[^\\p{L}\\p{N}]+", "");
        return t.isEmpty() ? l.replaceAll("[^\\p{L}\\p{N}]+", "") : t;
    }

    private static boolean sameTitle(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        return Math.min(a.length(), b.length()) >= 4 && (a.contains(b) || b.contains(a));
    }

    private static boolean sameArtist(String yt, String hit) {
        String ya = norm(yt);
        if (ya.isEmpty()) return false;
        for (String p : hit.split("[,&/]")) {
            String t = norm(p);
            if (!t.isEmpty() && (ya.contains(t) || t.contains(ya))) return true;
        }
        return false;
    }

    static boolean matches(String title, String artist, Hit h) {
        return sameTitle(norm(title), norm(h.title)) && sameArtist(artist, h.artist);
    }

    /** 카탈로그에서 확인되는 첫 순위(없으면 -1) */
    private static int hitIndex(JSONObject it, List<Hit> hits) {
        String t = it.optString("title"), a = it.optString("channel");
        int d = it.optInt("dur");
        int best = -1;
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            if (!matches(t, a, h)) continue;
            if (d > 0 && h.dur > 0 && Math.abs(d - h.dur) > 8) { if (best == -1) best = -2 - i; continue; } // 길이가 다르면(라이브/리믹스) 약하게
            return i;
        }
        return best <= -2 ? -best - 2 + 100 : -1;
    }

    static boolean hasMatch(JSONArray items, Hit h) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null && matches(o.optString("title"), o.optString("channel"), h)) return true;
        }
        return false;
    }

    /** 앨범에 등록되지 않은 곡 제거(카탈로그 확인 곡은 예외) → 중복 제거 → 카탈로그 일치 곡 우선 정렬 */
    static JSONArray rank(JSONArray ytm, List<Hit> hits, int max) {
        final int n = ytm.length();
        final double[] sc = new double[n];
        List<JSONObject> keep = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            JSONObject o = ytm.optJSONObject(i);
            if (o == null) continue;
            int hi = hitIndex(o, hits);
            boolean reg = !o.optString("albumId").isEmpty();
            if (!reg && hi < 0) continue;
            if (!seen.add(norm(o.optString("title")) + "|" + norm(o.optString("channel")))) continue;
            double s = 1.0 - i * 0.03;
            if (hi >= 0 && hi < 100) s += 2.0 + 1.0 / (1 + hi);
            else if (hi >= 100) s += 0.6;
            sc[keep.size()] = s;
            keep.add(o);
        }
        Integer[] idx = new Integer[keep.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer x, Integer y) { return Double.compare(sc[y], sc[x]); }
        });
        JSONArray out = new JSONArray();
        for (int i = 0; i < idx.length && out.length() < max; i++) out.put(keep.get(idx[i]));
        return out;
    }
}
