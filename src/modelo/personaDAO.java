package modelo;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class personaDAO {

    // Ruta del directorio y nombres de archivo para almacenamiento y exportación
    private static final String RUTA_DIRECTORIO = "src/modelo/gestionContactos";
    private static final String NOMBRE_ARCHIVO = "datosContactos.csv";
    private static final String NOMBRE_ARCHIVO_EXPORTACION = "contactos_exportados.csv";

    // Locks para sincronización de acceso a archivos y exportación
    private static final Object LOCK_ARCHIVO = new Object();
    private static final Object LOCK_EXPORTACION = new Object();

    private final File archivo;
    private final File archivoExportacion;
    private persona persona;

    public personaDAO() {
        this(null);
    }

    // Constructor que inicializa el DAO con un objeto persona y prepara los archivos necesarios
    public personaDAO(persona persona) {
        this.persona = persona;
        File directorio = new File(RUTA_DIRECTORIO);
        if (!directorio.exists()) {
            directorio.mkdirs();
        }
        this.archivo = new File(directorio, NOMBRE_ARCHIVO);
        this.archivoExportacion = new File(directorio, NOMBRE_ARCHIVO_EXPORTACION);
        prepararArchivo();
    }

    // Método privado para preparar el archivo de almacenamiento, creando el archivo si no existe
    private void prepararArchivo() {
        synchronized (LOCK_ARCHIVO) {
            if (!archivo.exists()) {
                try {
                    archivo.createNewFile();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public boolean escribirArchivo() {
        return persona != null && escribirArchivo(this.persona);
    }

    // Método privado para escribir los datos de una persona en el archivo de almacenamiento de manera sincronizada
    public synchronized boolean escribirArchivo(persona nuevaPersona) {
        synchronized (LOCK_ARCHIVO) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(archivo, true))) {
                writer.write(nuevaPersona.datosContacto());
                writer.newLine();
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    // Método para leer el archivo de almacenamiento y devolver una lista de objetos persona, manejando la sincronización para evitar conflictos de acceso concurrente
    public synchronized List<persona> leerArchivo() throws IOException {
        synchronized (LOCK_ARCHIVO) {
            List<persona> personas = new ArrayList<>();
            if (!archivo.exists() || archivo.length() == 0) {
                return personas;
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(archivo))) {
                String linea;
                while ((linea = reader.readLine()) != null) {
                    String[] datos = linea.split(";");
                    if (datos.length == 5) {
                        personas.add(new persona(
                                datos[0],
                                datos[1],
                                datos[2],
                                datos[3],
                                Boolean.parseBoolean(datos[4])
                        ));
                    }
                }
            }
            return personas;
        }
    }

    // Método para actualizar la lista completa de contactos en el archivo de almacenamiento, sobrescribiendo el contenido existente de manera sincronizada
    public synchronized void actualizarContactos(List<persona> personas) throws IOException {
        synchronized (LOCK_ARCHIVO) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(archivo, false))) {
                for (persona p : personas) {
                    writer.write(p.datosContacto());
                    writer.newLine();
                }
            }
        }
    }

    // Método para verificar si un contacto ya existe en el archivo de almacenamiento, comparando nombre, teléfono y correo electrónico de manera sincronizada
    public synchronized boolean existeContacto(persona candidato) throws IOException {
        List<persona> existentes = leerArchivo();
        for (persona p : existentes) {
            boolean mismoNombre = p.getNombre().equalsIgnoreCase(candidato.getNombre());
            boolean mismoTelefono = p.getTelefono().equalsIgnoreCase(candidato.getTelefono());
            boolean mismoEmail = p.getEmail().equalsIgnoreCase(candidato.getEmail());
            if (mismoNombre || mismoTelefono || mismoEmail) {
                return true;
            }
        }
        return false;
    }

    // Método para exportar la lista de contactos a un archivo CSV separado por punto y coma, asegurando que el acceso al archivo de exportación sea seguro mediante sincronización
    public boolean exportarContactosCSV(List<persona> listaExportar) {
        synchronized (LOCK_EXPORTACION) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(archivoExportacion, false))) {
                writer.write("Nombre;Telefono;Email;Categoria;Favorito");
                writer.newLine();
                for (persona p : listaExportar) {
                    writer.write(p.datosContacto());
                    writer.newLine();
                }
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
    }
    
    public String getRutaExportacion() {
        return archivoExportacion.getAbsolutePath();
    }
}