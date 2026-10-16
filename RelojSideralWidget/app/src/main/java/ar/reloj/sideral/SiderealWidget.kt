package ar.reloj.sideral

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.TypedValue
import android.widget.RemoteViews
import java.util.Calendar
import java.util.Locale

/**
 * Widget de 3 líneas:
 *   arriba  CAMBIABLE (tocar la línea para alternar): HL hora local / HS hora solar (amarillo) / UB ubicación
 *   ST      hora sideral local (sin cambios)
 *   EC      signo y grado (0h = 0° Aries; 18h = 0° Capricornio)
 *
 * Tocar el resto del widget abre una pantalla que actualiza la ubicación del teléfono.
 */
class SiderealWidget : AppWidgetProvider() {

    companion object {
        // ---- Valores por defecto (se usan hasta que el teléfono entregue su ubicación) ----
        // Mar del Plata. Este positivo / Oeste negativo, Norte positivo / Sur negativo.
        const val LONGITUD_DEFECTO = -57.5575
        const val LATITUD_DEFECTO = -38.0055

        /** Qué muestra la línea de arriba al instalar: 0 = HL local, 1 = HS solar, 2 = UB ubicación */
        const val MODO_INICIAL = 0

        /** true = hora solar verdadera (la del reloj de sol, con ecuación del tiempo).
         *  false = hora solar media (solo corrección por longitud). */
        const val SOLAR_VERDADERA = true

        /** Cada cuántos minutos se refresca el widget (más alto = menos batería). */
        const val INTERVALO_MIN = 5

        private const val ACTION_TICK = "ar.reloj.sideral.TICK"
        private const val ACTION_CAMBIAR = "ar.reloj.sideral.CAMBIAR"
        private const val PREFS = "reloj_prefs"
        private const val DIA_MS = 86400000L

        private val SIGNOS = arrayOf(
            "Ari", "Tau", "Gem", "Can", "Leo", "Vir",
            "Lib", "Esc", "Sag", "Cap", "Acu", "Pis"
        )

        /** Tiempo sideral medio en Greenwich, en grados (0..360). */
        fun gmstGrados(ms: Long): Double {
            val jd = ms / 86400000.0 + 2440587.5
            val d = jd - 2451545.0
            val t = d / 36525.0
            var g = 280.46061837 + 360.98564736629 * d + 0.000387933 * t * t - t * t * t / 38710000.0
            g %= 360.0
            if (g < 0) g += 360.0
            return g
        }

        /** Ecuación del tiempo en minutos (hora solar verdadera = media + esto). */
        fun ecuacionDelTiempoMin(ms: Long): Double {
            val d = (ms / 86400000.0 + 2440587.5) - 2451545.0
            val rad = Math.PI / 180.0
            val l0 = (280.460 + 0.9856474 * d) % 360.0
            val g = (357.528 + 0.9856003 * d) * rad
            val lambda = (l0 + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g)) * rad
            val eps = (23.439 - 0.0000004 * d) * rad
            val alfa = Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda)) / rad
            var dif = (l0 - alfa) % 360.0
            if (dif > 180.0) dif -= 360.0
            if (dif < -180.0) dif += 360.0
            return dif * 4.0
        }

        /** Hora solar local, en milisegundos desde las 00:00. */
        fun horaSolarMs(ms: Long, lonGrados: Double): Long {
            val utc = ((ms % DIA_MS) + DIA_MS) % DIA_MS
            var s = utc.toDouble() + lonGrados / 15.0 * 3600000.0
            if (SOLAR_VERDADERA) s += ecuacionDelTiempoMin(ms) * 60000.0
            s %= DIA_MS.toDouble()
            if (s < 0) s += DIA_MS.toDouble()
            return s.toLong()
        }

        private fun hhmm(ms: Long): String =
            "%02d:%02d".format((ms / 3600000L).toInt(), ((ms / 60000L) % 60L).toInt())

        /** Grados y minutos sin signo: 57.5575 -> 57°33' */
        private fun gm(v: Double): String {
            val a = Math.abs(v)
            var g = a.toInt()
            var m = Math.round((a - g) * 60.0).toInt()
            if (m == 60) { g += 1; m = 0 }
            return String.format(Locale.US, "%d°%02d'", g, m)
        }

        /** Coordenadas compactas: 57°33'W 38°00'S (W oeste, E este, S sur, N norte). */
        fun coordenadas(lat: Double, lon: Double): String =
            gm(lon) + (if (lon < 0) "W" else "E") + " " +
                gm(lat) + (if (lat < 0) "S" else "N")

        fun guardarUbicacion(context: Context, lat: Double, lon: Double) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat("lat", lat.toFloat())
                .putFloat("lon", lon.toFloat())
                .putBoolean("real", true)
                .apply()
        }

        fun actualizar(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, SiderealWidget::class.java))
            if (ids.isEmpty()) return

            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val real = sp.getBoolean("real", false)
            val lon = if (real) sp.getFloat("lon", LONGITUD_DEFECTO.toFloat()).toDouble() else LONGITUD_DEFECTO
            val lat = if (real) sp.getFloat("lat", LATITUD_DEFECTO.toFloat()).toDouble() else LATITUD_DEFECTO
            val modo = sp.getInt("modo", MODO_INICIAL)

            val ahora = System.currentTimeMillis()

            // Hora local
            val cal = Calendar.getInstance().apply { timeInMillis = ahora }
            val hl = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))

            // Hora sideral local
            var lst = (gmstGrados(ahora) + lon) % 360.0
            if (lst < 0) lst += 360.0
            val horas = lst / 15.0
            val h = horas.toInt()
            val m = ((horas - h) * 60.0).toInt()
            val st = "%02d:%02d".format(h, m)

            // Signo y grado
            val signo = SIGNOS[(lst / 30.0).toInt() % 12]
            val grado = (lst % 30.0).toInt()
            val ec = "$signo %02d°".format(grado)

            // Línea de arriba según el modo. El "*" indica que aún se usa la ubicación por defecto.
            val sufijo = if (real) "" else "*"
            val (etiqueta, textoTop, tam) = when (modo) {
                1 -> Triple("HS$sufijo", hhmm(horaSolarMs(ahora, lon)), 34f)
                2 -> Triple("UB$sufijo", coordenadas(lat, lon), 15f)
                else -> Triple("HL", hl, 34f)
            }
            // La hora solar va en amarillo para distinguirla de la hora local (blanca).
            val amarillo = Color.parseColor("#FFD600")
            val colorValor = if (modo == 1) amarillo else Color.WHITE
            val colorEtiqueta = if (modo == 1) amarillo else Color.parseColor("#99FFFFFF")

            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setTextViewText(R.id.top_label, etiqueta)
            views.setTextViewText(R.id.top, textoTop)
            views.setTextViewTextSize(R.id.top, TypedValue.COMPLEX_UNIT_SP, tam)
            views.setTextColor(R.id.top, colorValor)
            views.setTextColor(R.id.top_label, colorEtiqueta)
            views.setTextViewText(R.id.st, st)
            views.setTextViewText(R.id.ec, ec)
            views.setOnClickPendingIntent(R.id.raiz, pendienteUbicacion(context))
            views.setOnClickPendingIntent(R.id.fila_top, pendienteCambiar(context))
            mgr.updateAppWidget(ids, views)
        }

        private fun pendienteCambiar(context: Context): PendingIntent {
            val intent = Intent(context, SiderealWidget::class.java).setAction(ACTION_CAMBIAR)
            return PendingIntent.getBroadcast(
                context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun pendienteUbicacion(context: Context): PendingIntent {
            val intent = Intent(context, UbicacionActivity::class.java)
            return PendingIntent.getActivity(
                context, 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun pendiente(context: Context): PendingIntent {
            val intent = Intent(context, SiderealWidget::class.java).setAction(ACTION_TICK)
            return PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** Programa la próxima actualización en el siguiente múltiplo de INTERVALO_MIN minutos. */
        private fun programar(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val paso = INTERVALO_MIN * 60000L
            val proximo = (System.currentTimeMillis() / paso + 1) * paso + 300L
            alarm.setAndAllowWhileIdle(AlarmManager.RTC, proximo, pendiente(context))
        }

        private fun cancelar(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.cancel(pendiente(context))
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        programar(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelar(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        actualizar(context)
        programar(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TICK, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> {
                actualizar(context)
                programar(context)
            }
            ACTION_CAMBIAR -> {
                val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val nuevo = (sp.getInt("modo", MODO_INICIAL) + 1) % 3
                sp.edit().putInt("modo", nuevo).apply()
                actualizar(context)
            }
        }
    }
}
