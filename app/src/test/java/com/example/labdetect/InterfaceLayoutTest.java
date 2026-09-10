package com.example.labdetect;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.*;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.EditText;
import org.robolectric.shadows.ShadowDialog;
import com.example.labdetect.domain.EquipmentProfile;
import androidx.appcompat.view.ContextThemeWrapper;
import com.example.labdetect.domain.Detection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class InterfaceLayoutTest {
    private View inflate(int layout) {
        return LayoutInflater.from(new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_LabDetect))
            .inflate(layout, null, false);
    }

    private void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private void save(View view, String name) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        File folder = new File("build/reports/ui");
        folder.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
    }

    private View camera(int width, int height, String name) throws Exception {
        View view = inflate(R.layout.fragment_camera);
        view.findViewById(R.id.answerCard).setVisibility(View.VISIBLE);
        view.findViewById(R.id.tvQuestionPrompt).setVisibility(View.VISIBLE);
        view.findViewById(R.id.tvScanStatus).setVisibility(View.INVISIBLE);
        if (height < 400) {
            view.findViewById(R.id.answerCard).setVisibility(View.GONE);
            view.findViewById(R.id.tvQuestionPrompt).setVisibility(View.GONE);
        }
        ((TextView)view.findViewById(R.id.tvCameraAnswer)).setText("La balanza analítica se usa para medir masas con alta precisión en el laboratorio.");
        ((DetectionOverlayView)view.findViewById(R.id.detectionOverlay)).submitDetections(
            Collections.singletonList(new Detection("balanza_analitica", "Balanza analítica", 94f, .2f, .14f, .8f, .62f, true)));
        layout(view, width, height);
        View composer = view.findViewById(R.id.conversationCard);
        View send = view.findViewById(R.id.btnSendQuestion);
        assertTrue("Composer has usable width", composer.getWidth() > width / 2);
        assertTrue("Send target remains visible", send.getWidth() >= 48);
        assertNotNull("Send has an icon", ((ImageView)send).getDrawable());
        assertNotNull("Favorites has an icon", ((ImageView)view.findViewById(R.id.btnFavoritesList)).getDrawable());
        assertTrue("Composer stays above the keyboard", view.findViewById(R.id.conversationScroll).getBottom() <= height);
        assertTrue("Send does not overlap input", view.findViewById(R.id.tilCameraQuestion).getRight() <= send.getLeft());
        save(view, name);
        return view;
    }

    @Test @Config(qualifiers="w360dp-h740dp-mdpi")
    public void cameraAndCompactKeyboard() throws Exception {
        camera(360, 740, "camera-360");
        camera(360, 330, "camera-keyboard");
        View detail = inflate(R.layout.fragment_detail);
        ((TextView)detail.findViewById(R.id.tvDetailTitle)).setText("Balanza analítica");
        ((TextView)detail.findViewById(R.id.tvOfflineCharacteristics)).setText("Mide masas con alta precisión. Consulta su documentación guardada.");
        layout(detail, 360, 740); save(detail, "detail-360");
    }

    @Test @Config(qualifiers="w320dp-h640dp-mdpi")
    public void smallPhoneAndLargeText() throws Exception {
        RuntimeEnvironment.setFontScale(1.5f);
        camera(320, 640, "camera-large-text");
    }

    @Test @Config(qualifiers="w800dp-h1000dp-mdpi")
    public void tabletComposerIsCapped() throws Exception {
        View view = camera(800, 1000, "camera-tablet");
        assertTrue(view.findViewById(R.id.conversationScroll).getWidth() <= 600);
        View detail = inflate(R.layout.fragment_detail);
        layout(detail, 800, 1000);
        assertTrue(detail.findViewById(R.id.detailScroll).getWidth() <= 720);
    }

    @Test @Config(qualifiers="w360dp-h740dp-mdpi")
    public void localReaderAndFavorites() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.Theme_LabDetect);
        LabSheets.INSTANCE.reader(activity, "Balanza analítica", "Información guardada.\n\n" +
            String.join("\n\n", Collections.nCopies(15, "Consulta las instrucciones específicas del fabricante para este equipo.")),
            "Manual local · disponible sin internet");
        android.app.Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        View decor = dialog.getWindow().getDecorView();
        layout(decor, 360, 740); save(decor, "manual-local");
        dialog.dismiss();
        LabSheets.INSTANCE.favorites(activity, Collections.singletonList(
            new EquipmentProfile("balanza", "Balanza analítica", Collections.emptyList())),
            id -> true, profile -> kotlin.Unit.INSTANCE);
        dialog = ShadowDialog.getLatestDialog();
        decor = dialog.getWindow().getDecorView();
        layout(decor, 360, 740); save(decor, "favorites");
        EditText search = findSearch(decor);
        assertNotNull(search);
        search.setText("no-existe");
        layout(decor, 360, 740); save(decor, "favorites-empty-search");
        assertTrue(containsText(decor, "No hay equipos con ese nombre."));
        dialog.dismiss();
        activity.finish();
    }

    private EditText findSearch(View view) {
        if (view instanceof EditText) return (EditText)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            EditText result = findSearch(((ViewGroup)view).getChildAt(i));
            if (result != null) return result;
        }
        return null;
    }

    private boolean containsText(View view, String text) {
        if (view instanceof TextView && ((TextView)view).getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++)
            if (containsText(((ViewGroup)view).getChildAt(i), text)) return true;
        return false;
    }
}
