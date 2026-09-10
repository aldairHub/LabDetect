package com.example.labdetect

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import com.example.labdetect.domain.EquipmentProfile
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Shared reader and collection surfaces use the same typography and glass treatment. */
object LabSheets {
    fun show(context: Context, title: String, subtitle: String, content: (LinearLayout, BottomSheetDialog) -> Unit) {
        val dp = context.resources.displayMetrics.density
        val dialog = BottomSheetDialog(context)
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt())
            setBackgroundResource(R.drawable.bg_page)
        }
        page.addView(View(context).apply { setBackgroundColor(Color.parseColor("#52675B")) },
            LinearLayout.LayoutParams((32 * dp).toInt(), (3 * dp).toInt()).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = (12 * dp).toInt()
            })
        val header = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = title; textSize = 23f; setTextColor(Color.parseColor("#F4F8F5"))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(androidx.appcompat.widget.AppCompatImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            contentDescription = "Cerrar"
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))
        page.addView(header)
        page.addView(TextView(context).apply {
            text = subtitle; textSize = 13f; setTextColor(Color.parseColor("#A8B8AF"))
            setPadding(0, (4 * dp).toInt(), 0, (16 * dp).toInt())
        })
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content(body, dialog)
        page.addView(ScrollView(context).apply { isFillViewport = false; addView(body) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(page)
        dialog.setOnShowListener {
            val screenHeight = context.resources.displayMetrics.heightPixels
            page.layoutParams = page.layoutParams.apply { height = (screenHeight * .85f).toInt() }
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
            dialog.behavior.maxWidth = (720 * dp).toInt()
            dialog.window?.setDimAmount(.4f)
        }
        dialog.show()
    }

    fun reader(context: Context, title: String, text: String, source: String = "Manual local · disponible sin internet") {
        show(context, title, source) { body, _ ->
            body.addView(TextView(context).apply {
                this.text = text.ifBlank { "Esta sección todavía no está disponible." }
                textSize = 17f
                setTextColor(Color.parseColor("#EDF3EF"))
                setLineSpacing(0f, 1.35f)
                setTextIsSelectable(true)
                setPadding(0, 8, 0, 32)
            })
        }
    }

    fun favorites(context: Context, profiles: List<EquipmentProfile>, isFavorite: (String) -> Boolean, onSelect: (EquipmentProfile) -> Unit) {
        show(context, "Tu laboratorio", "Favoritos y equipos recientes") { body, dialog ->
            val dp = context.resources.displayMetrics.density
            val search = EditText(context).apply {
                hint = "Buscar equipo"; textSize = 16f; setSingleLine(true)
                setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#A8B8AF"))
                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
                setBackgroundResource(R.drawable.bg_glass)
                contentDescription = "Buscar en favoritos y recientes"
            }
            body.addView(search, LinearLayout.LayoutParams(-1, -2))
            val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            body.addView(rows)
            fun render(query: String) {
                rows.removeAllViews()
                val selected = profiles.filter { it.displayName.contains(query, true) }
                if (selected.isEmpty()) rows.addView(TextView(context).apply {
                    text = if (profiles.isEmpty()) "Tu colección empieza aquí. Reconoce un equipo y guárdalo desde su ficha." else "No hay equipos con ese nombre."
                    textSize = 16f; setTextColor(Color.parseColor("#A8B8AF"))
                    setPadding(0, (24 * dp).toInt(), 0, 0)
                })
                selected.forEach { profile ->
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
                        setBackgroundResource(R.drawable.bg_glass)
                        isFocusable = true
                        setOnClickListener { dialog.dismiss(); onSelect(profile) }
                        contentDescription = profile.displayName + if (isFavorite(profile.id)) ", favorito" else ", reciente"
                    }
                    val thumbnail = java.io.File(context.filesDir, "equipment-thumbnails/${profile.id}.jpg")
                    row.addView(ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        if (thumbnail.isFile) setImageBitmap(android.graphics.BitmapFactory.decodeFile(thumbnail.path))
                        else { setImageResource(R.drawable.ic_book); imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#55D977")) }
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams((48 * dp).toInt(), (56 * dp).toInt()).apply { marginEnd = (12 * dp).toInt() })
                    row.addView(TextView(context).apply {
                        text = profile.displayName + "\n" + if (isFavorite(profile.id)) "★ Favorito" else "Visto recientemente"
                        textSize = 16f; setTextColor(Color.parseColor("#EDF3EF")); setLineSpacing(0f, 1.25f)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * dp).toInt() })
                }
            }
            search.doAfterTextChanged { render(it.toString()) }
            render("")
        }
    }
}
