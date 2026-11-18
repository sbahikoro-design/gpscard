package ci.anader.webmap;

/*
 * WebMapANADER — version Android connectée.
 *
 * L'application ouvre la plateforme publiée sur GitHub Pages dans une WebView
 * réglée pour un usage de terrain : géolocalisation, export de fichiers vers
 * le dossier Téléchargements, import de fichiers, reprise en cache lorsque le
 * réseau manque.
 *
 * ANADER — DMAOPAG / DGRM / Cellule Géomatique.
 */

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    /** Adresse de la plateforme en ligne. */
    private static final String ACCUEIL = "https://sbahikoro-design.github.io/gpscard/";
    /** Hôte autorisé à rester dans l'application. */
    private static final String HOTE = "sbahikoro-design.github.io";

    private static final int DEM_POSITION = 11;
    private static final int DEM_ECRITURE = 12;
    private static final int CHOIX_FICHIER = 21;

    private WebView vue;
    private ProgressBar jauge;
    private View panneauErreur;

    private ValueCallback<Uri[]> retourFichier;
    private String origineEnAttente;
    private GeolocationPermissions.Callback repriseGeo;
    private String dataEnAttente, nomEnAttente;
    private boolean replierSurCache = false;

    /* ------------------------------------------------------------------ */

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);

        FrameLayout racine = new FrameLayout(this);
        racine.setBackgroundColor(Color.WHITE);

        vue = new WebView(this);
        racine.addView(vue, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        jauge = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        jauge.setMax(100);
        racine.addView(jauge, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 12, Gravity.TOP));

        racine.addView(construireErreur(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(racine);

        reglerWebView();
        vue.addJavascriptInterface(new Pont(), "AnaderAndroid");

        if (etat != null) {
            vue.restoreState(etat);
        } else {
            vue.loadUrl(ACCUEIL);
        }
    }

    private View construireErreur() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER);
        p.setBackgroundColor(Color.WHITE);
        p.setPadding(48, 48, 48, 48);
        p.setVisibility(View.GONE);

        TextView t = new TextView(this);
        t.setText("Plateforme injoignable.\n\nVérifiez la connexion, puis réessayez. "
                + "La dernière version consultée est rouverte automatiquement si elle "
                + "se trouve encore en mémoire.");
        t.setTextSize(16f);
        t.setTextColor(Color.parseColor("#333333"));
        t.setGravity(Gravity.CENTER);
        p.addView(t);

        Button b = new Button(this);
        b.setText("Réessayer");
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                panneauErreur.setVisibility(View.GONE);
                replierSurCache = false;
                vue.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
                vue.loadUrl(ACCUEIL);
            }
        });
        p.addView(b);

        panneauErreur = p;
        return p;
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void reglerWebView() {
        WebSettings s = vue.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        vue.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView w, String url) {
                return horsPlateforme(url);
            }

            @Override
            @android.annotation.TargetApi(Build.VERSION_CODES.N)
            public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                return horsPlateforme(r.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView w, String url) {
                jauge.setVisibility(View.GONE);
                if (url != null && url.contains(HOTE)) {
                    w.evaluateJavascript(SCRIPT_EXPORT, null);
                }
            }

            @Override
            public void onReceivedError(WebView w, int code, String description, String url) {
                if (url != null && url.startsWith(ACCUEIL.substring(0, 8)) && !replierSurCache) {
                    /* une seule tentative de reprise sur le cache */
                    replierSurCache = true;
                    w.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
                    w.loadUrl(ACCUEIL);
                    annonce("Hors connexion — ouverture de la dernière version en mémoire");
                } else if (!reseauDisponible()) {
                    panneauErreur.setVisibility(View.VISIBLE);
                }
            }
        });

        vue.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onProgressChanged(WebView w, int p) {
                jauge.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
                jauge.setProgress(p);
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origine,
                                                           GeolocationPermissions.Callback cb) {
                if (positionAccordee()) {
                    cb.invoke(origine, true, false);
                } else {
                    origineEnAttente = origine;
                    repriseGeo = cb;
                    demander(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, DEM_POSITION);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (retourFichier != null) retourFichier.onReceiveValue(null);
                retourFichier = cb;
                try {
                    startActivityForResult(params.createIntent(), CHOIX_FICHIER);
                } catch (ActivityNotFoundException e) {
                    retourFichier = null;
                    annonce("Aucune application de fichiers disponible");
                    return false;
                }
                return true;
            }
        });

        /* Téléchargements classiques (liens http) et liens data: */
        vue.setDownloadListener((url, agent, disposition, type, taille) -> {
            if (url == null) return;
            if (url.startsWith("data:")) {
                enregistrerDataUrl(url, nomDepuisDisposition(disposition, type));
            } else if (url.startsWith("blob:")) {
                annonce("Relancez l'export depuis le bouton de la plateforme");
            } else {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException e) {
                    annonce("Téléchargement impossible");
                }
            }
        });
    }

    /**
     * Intercepte les exports de la plateforme (Excel, planches, GeoJSON) qui
     * passent par un lien blob: ou data: — une WebView ne sait pas les
     * enregistrer seule.
     */
    private static final String SCRIPT_EXPORT =
            "(function(){if(window.__anaderExport)return;window.__anaderExport=1;"
          + "document.addEventListener('click',function(e){"
          + " var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;"
          + " if(!a)return; var h=a.getAttribute('href')||'';"
          + " if(h.indexOf('blob:')!==0&&h.indexOf('data:')!==0)return;"
          + " e.preventDefault();e.stopPropagation();"
          + " var nom=a.getAttribute('download')||'webmapanader';"
          + " fetch(h).then(function(r){return r.blob();}).then(function(b){"
          + "  var fr=new FileReader();"
          + "  fr.onloadend=function(){AnaderAndroid.enregistrer(String(fr.result),nom);};"
          + "  fr.readAsDataURL(b);"
          + " }).catch(function(err){AnaderAndroid.signaler('Export impossible : '+err);});"
          + "},true);})();";

    /** Passerelle appelée par le script ci-dessus. */
    private class Pont {
        @JavascriptInterface
        public void enregistrer(final String dataUrl, final String nom) {
            runOnUiThread(() -> enregistrerDataUrl(dataUrl, nom));
        }

        @JavascriptInterface
        public void signaler(final String message) {
            runOnUiThread(() -> annonce(message));
        }
    }

    /* ------------------------------------------------------------------ */

    private boolean horsPlateforme(String url) {
        if (url == null) return false;
        if (url.startsWith("http") && !url.contains(HOTE)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            } catch (ActivityNotFoundException e) {
                return false;
            }
        }
        if (url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("geo:")) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            } catch (ActivityNotFoundException e) {
                return false;
            }
        }
        return false;
    }

    private String nomDepuisDisposition(String disposition, String type) {
        if (disposition != null) {
            int i = disposition.indexOf("filename=");
            if (i >= 0) {
                String n = disposition.substring(i + 9).replace("\"", "").trim();
                if (!n.isEmpty()) return n;
            }
        }
        String ext = ".bin";
        if (type != null) {
            if (type.contains("sheet") || type.contains("excel")) ext = ".xlsx";
            else if (type.contains("png")) ext = ".png";
            else if (type.contains("json")) ext = ".json";
            else if (type.contains("csv")) ext = ".csv";
            else if (type.contains("pdf")) ext = ".pdf";
        }
        return "webmapanader_" + System.currentTimeMillis() + ext;
    }

    /** Écrit un contenu data:…;base64,… dans le dossier Téléchargements. */
    private void enregistrerDataUrl(String dataUrl, String nom) {
        try {
            int virgule = dataUrl.indexOf(',');
            if (virgule < 0) { annonce("Export illisible"); return; }
            String entete = dataUrl.substring(0, virgule);
            byte[] donnees = Base64.decode(dataUrl.substring(virgule + 1), Base64.DEFAULT);
            String type = "application/octet-stream";
            int p = entete.indexOf(':');
            int q = entete.indexOf(';');
            if (p >= 0 && q > p) type = entete.substring(p + 1, q);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, nom);
                v.put(MediaStore.Downloads.MIME_TYPE, type);
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri cible = getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (cible == null) { annonce("Enregistrement refusé"); return; }
                OutputStream os = getContentResolver().openOutputStream(cible);
                os.write(donnees);
                os.close();
                v.clear();
                v.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(cible, v, null, null);
            } else {
                if (!ecritureAccordee()) {
                    dataEnAttente = dataUrl;
                    nomEnAttente = nom;
                    demander(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            DEM_ECRITURE);
                    return;
                }
                File dossier = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!dossier.exists() && !dossier.mkdirs()) {
                    annonce("Dossier Téléchargements introuvable");
                    return;
                }
                FileOutputStream fos = new FileOutputStream(new File(dossier, nom));
                fos.write(donnees);
                fos.close();
            }
            annonce("Enregistré dans Téléchargements : " + nom);
        } catch (Exception e) {
            annonce("Enregistrement impossible : " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */

    private boolean positionAccordee() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                   == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                   == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ecritureAccordee() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                   == PackageManager.PERMISSION_GRANTED;
    }

    private void demander(String[] droits, int code) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(droits, code);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] droits, int[] resultats) {
        super.onRequestPermissionsResult(code, droits, resultats);
        boolean accorde = resultats.length > 0
                && resultats[0] == PackageManager.PERMISSION_GRANTED;

        if (code == DEM_POSITION && repriseGeo != null) {
            repriseGeo.invoke(origineEnAttente, accorde, false);
            repriseGeo = null;
            origineEnAttente = null;
            if (!accorde) annonce("Sans autorisation de position, « Où suis-je ? » reste inactif");
        } else if (code == DEM_ECRITURE) {
            if (accorde && dataEnAttente != null) {
                enregistrerDataUrl(dataEnAttente, nomEnAttente);
            } else {
                annonce("Enregistrement refusé faute d'autorisation");
            }
            dataEnAttente = null;
            nomEnAttente = null;
        }
    }

    @Override
    protected void onActivityResult(int code, int resultat, Intent donnees) {
        super.onActivityResult(code, resultat, donnees);
        if (code != CHOIX_FICHIER) return;
        if (retourFichier == null) return;
        Uri[] choix = null;
        if (resultat == RESULT_OK && donnees != null) {
            if (donnees.getDataString() != null) {
                choix = new Uri[]{Uri.parse(donnees.getDataString())};
            } else if (donnees.getClipData() != null) {
                int n = donnees.getClipData().getItemCount();
                choix = new Uri[n];
                for (int i = 0; i < n; i++) {
                    choix[i] = donnees.getClipData().getItemAt(i).getUri();
                }
            }
        }
        retourFichier.onReceiveValue(choix);
        retourFichier = null;
    }

    /* ------------------------------------------------------------------ */

    private boolean reseauDisponible() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo n = cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    private void annonce(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public boolean onKeyDown(int touche, KeyEvent evenement) {
        if (touche == KeyEvent.KEYCODE_BACK && vue != null && vue.canGoBack()) {
            vue.goBack();
            return true;
        }
        return super.onKeyDown(touche, evenement);
    }

    @Override
    protected void onSaveInstanceState(Bundle etat) {
        super.onSaveInstanceState(etat);
        vue.saveState(etat);
    }

    @Override
    protected void onPause() {
        super.onPause();
        vue.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        vue.onResume();
    }

    @Override
    protected void onDestroy() {
        if (vue != null) {
            vue.destroy();
            vue = null;
        }
        super.onDestroy();
    }
}
