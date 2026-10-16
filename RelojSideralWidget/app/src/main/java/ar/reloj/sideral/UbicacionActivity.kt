package ar.reloj.sideral

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

/**
 * Pantalla mínima: pide el permiso de ubicación (si falta), lee la posición del teléfono,
 * la guarda para el widget y se cierra sola. Se abre al tocar el widget o desde el ícono.
 */
class UbicacionActivity : Activity() {

    private lateinit var texto: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null
    private var guardada = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        texto = TextView(this).apply {
            textSize = 18f
            setPadding(48, 96, 48, 48)
        }
        setContentView(texto)

        if (tienePermiso()) {
            obtener()
        } else {
            mostrar("Se necesita el permiso de ubicación.")
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                1
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (tienePermiso()) {
            obtener()
        } else {
            mostrar("Sin permiso de ubicación el widget usa Mar del Plata por defecto (marcado con *).\n\nPodés darlo en Ajustes > Apps > Reloj Sideral > Permisos.")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        detener()
        super.onDestroy()
    }

    private fun tienePermiso(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun mostrar(s: String) {
        texto.text = s
    }

    private fun coords(lat: Double, lon: Double): String =
        SiderealWidget.coordenadas(lat, lon)

    private fun guardar(loc: Location) {
        SiderealWidget.guardarUbicacion(this, loc.latitude, loc.longitude)
        SiderealWidget.actualizar(this)
        guardada = true
    }

    private fun cerrarEn(ms: Long) {
        handler.postDelayed({ finish() }, ms)
    }

    private fun detener() {
        val l = listener ?: return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(l)
        } catch (e: Exception) {
            // nada
        }
        listener = null
    }

    @Suppress("DEPRECATION")
    private fun obtener() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 1) Última ubicación conocida: se guarda ya, para que el widget no quede esperando.
        try {
            var mejor: Location? = null
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                val m = mejor
                if (m == null || l.time > m.time) mejor = l
            }
            val elegida = mejor
            if (elegida != null) {
                guardar(elegida)
                mostrar("Ubicación guardada\n" + coords(elegida.latitude, elegida.longitude) + "\n\nAfinando…")
            }
        } catch (e: SecurityException) {
            mostrar("No hay permiso de ubicación.")
            return
        }

        // 2) Pedir una posición fresca.
        val proveedor: String? = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (proveedor == null) {
            if (guardada) {
                cerrarEn(2500)
            } else {
                mostrar("Activá la ubicación (GPS) del teléfono y tocá el widget de nuevo.")
            }
            return
        }
        if (!guardada) mostrar("Buscando ubicación…")

        val nuevo = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                listener = null
                handler.removeCallbacksAndMessages(null)
                guardar(location)
                mostrar("Ubicación actualizada\n" + coords(location.latitude, location.longitude))
                cerrarEn(1800)
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        listener = nuevo
        try {
            lm.requestSingleUpdate(proveedor, nuevo, Looper.getMainLooper())
        } catch (e: SecurityException) {
            mostrar("No hay permiso de ubicación.")
            return
        }

        // Si en 30 s no llega nada, se cierra con lo que haya.
        handler.postDelayed({
            detener()
            if (guardada) {
                cerrarEn(0)
            } else {
                mostrar("No se pudo obtener la ubicación. Probá al aire libre y tocá el widget de nuevo.")
            }
        }, 30000L)
    }
}
