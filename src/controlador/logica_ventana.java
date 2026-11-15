package controlador;

import java.awt.Color;
import java.awt.event.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import modelo.persona;
import modelo.personaDAO;
import vista.ventana;

public class logica_ventana implements ActionListener {

    private final ventana delegado;
    private final personaDAO dao;
    private List<persona> contactos;
    private TableRowSorter<DefaultTableModel> sorter;

    // Hilos para tareas en segundo plano (validación, modificación)
    private final ExecutorService segundoPlano = Executors.newCachedThreadPool();

    // SwingWorker activo para búsqueda en segundo plano
    private SwingWorker<Integer, Void> busquedaSegundoPlano;

    // bloqueo por contacto para evitar edición 
    private static final ConcurrentHashMap<String, ReentrantLock> bloqueoContactos = new ConcurrentHashMap<>();

    // notificación para evitar que un hilo antiguo borre un mensaje nuevo
    private volatile long idUltimaNotificacion = 0;

    // Constructor que inicializa la lógica de la ventana y carga los contactos existentes
    public logica_ventana(ventana delegado) {
        this.delegado = delegado;
        this.dao = new personaDAO();
        this.contactos = new ArrayList<>();
        cargarContactos();
        configurarBotones();
        configurarCambioIdioma();
        configurarMenuContextualYTabla();
        configurarAtajosTeclado();
        configurarBuscador();
    }

    // Configuración de los botones y sus listeners
    private void configurarBotones() {
        delegado.btn_add.addActionListener(this);
        delegado.btn_eliminar.addActionListener(this);
        delegado.btn_modificar.addActionListener(this);
        delegado.btn_limpiar.addActionListener(this);
        if (delegado.btn_exportar != null) {
            delegado.btn_exportar.addActionListener(this);
        }
    }

    // Configuración del cambio de idioma mediante el combo box
    private void configurarCambioIdioma() {
        delegado.cmb_idioma.addActionListener(e -> {
            String seleccionado = String.valueOf(delegado.cmb_idioma.getSelectedItem());
            Locale nuevoIdioma;
            switch (seleccionado) {
                case "English":
                    nuevoIdioma = new Locale("en", "US");
                    break;
                case "Kichwa":
                    nuevoIdioma = new Locale("qu", "EC");
                    break;
                default:
                    nuevoIdioma = new Locale("es", "ES");
                    break;
            }
            segundoPlano.shutdown();
            delegado.dispose();
            new ventana(nuevoIdioma).setVisible(true);
        });
    }

    // Configuración del menú contextual y la tabla de contactos
    private void configurarMenuContextualYTabla() {
        JPopupMenu menuContextual = new JPopupMenu();
        JMenuItem itemModificar = new JMenuItem("Modificar Contacto");
        JMenuItem itemEliminar = new JMenuItem("Eliminar Contacto");
        menuContextual.add(itemModificar);
        menuContextual.add(itemEliminar);

        itemModificar.addActionListener(e -> delegado.btn_modificar.doClick());
        itemEliminar.addActionListener(e -> delegado.btn_eliminar.doClick());

        // Listener para mostrar el menú contextual al hacer clic derecho en la tabla de contactos
        delegado.tbl_contactos.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int row = delegado.tbl_contactos.rowAtPoint(e.getPoint());
                if (row >= 0 && row < delegado.tbl_contactos.getRowCount()) {
                    delegado.tbl_contactos.setRowSelectionInterval(row, row);
                    cargarContactoEnFormulario();
                    if (SwingUtilities.isRightMouseButton(e)) {
                        menuContextual.show(e.getComponent(), e.getX(), e.getY());
                    }
                } else {
                    limpiarCampos();
                }
            }
        });

        // Listener para deseleccionar la fila seleccionada al hacer clic fuera de la tabla
        MouseAdapter deseleccionarListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                limpiarCampos();
            }
        };
        delegado.contentPane.addMouseListener(deseleccionarListener);
        delegado.tbl_contactos.getParent().addMouseListener(deseleccionarListener);
    }

    // Configuración de atajos de teclado para acciones rápidas
    private void configurarAtajosTeclado() {
        asignarAtajo(delegado.contentPane, JComponent.WHEN_IN_FOCUSED_WINDOW,
                KeyEvent.VK_ENTER, "accionEnter", () -> {
                    if (delegado.txt_buscar.isFocusOwner()) {
                        buscarPorNombre(true);
                    } else {
                        delegado.btn_add.doClick();
                    }
                });
        asignarAtajo(delegado.tbl_contactos, JComponent.WHEN_FOCUSED,
                KeyEvent.VK_DELETE, "eliminar", () -> delegado.btn_eliminar.doClick());
    }

    // Método auxiliar para asignar atajos de teclado a componentes específicos
    private void asignarAtajo(JComponent componente, int condicion, int tecla, String nombreAccion, Runnable accion) {
        KeyStroke keyStroke = KeyStroke.getKeyStroke(tecla, 0);
        componente.getInputMap(condicion).put(keyStroke, nombreAccion);
        componente.getActionMap().put(nombreAccion, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                accion.run();
            }
        });
    }

    // Configuración del buscador de contactos con búsqueda en segundo plano
    private void configurarBuscador() {
        sorter = new TableRowSorter<>(delegado.modeloTabla);
        delegado.tbl_contactos.setRowSorter(sorter);
        delegado.txt_buscar.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { buscarPorNombre(false); }
            @Override
            public void removeUpdate(DocumentEvent e) { buscarPorNombre(false); }
            @Override
            public void changedUpdate(DocumentEvent e) { buscarPorNombre(false); }
        });
    }

    // Búsqueda de contactos por nombre, teléfono o email en segundo plano con notificación en tiempo real
    private void buscarPorNombre(boolean mostrarAlertaNoExiste) {
        final String texto = delegado.txt_buscar.getText().trim().toLowerCase();

        if (busquedaSegundoPlano != null && !busquedaSegundoPlano.isDone()) {
            busquedaSegundoPlano.cancel(true);
        }

        if (texto.isEmpty()) {
            sorter.setRowFilter(null);
            limpiarCampos();
            return;
        }

        final List<persona> copiaContactos;
        synchronized (this) {
            copiaContactos = new ArrayList<>(contactos);
        }

        // Búsqueda en segundo plano para evitar bloquear la interfaz de usuario
        busquedaSegundoPlano = new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() {
                for (int i = 0; i < copiaContactos.size(); i++) {
                    if (isCancelled()) {
                        return -1;
                    }
                    persona p = copiaContactos.get(i);
                    if (p.getNombre().toLowerCase().contains(texto)
                            || p.getTelefono().toLowerCase().contains(texto)
                            || p.getEmail().toLowerCase().contains(texto)) {
                        return i;
                    }
                }
                return -1;
            }

            // Actualización de la interfaz de usuario después de la búsqueda en segundo plano
            @Override
            protected void done() {
                if (isCancelled()) {
                    return;
                }
                try {
                    int indiceModelo = get();
                    if (indiceModelo >= 0 && indiceModelo < delegado.modeloTabla.getRowCount()) {
                        int filaVista = delegado.tbl_contactos.convertRowIndexToView(indiceModelo);
                        if (filaVista >= 0) {
                            delegado.tbl_contactos.setRowSelectionInterval(filaVista, filaVista);
                            delegado.tbl_contactos.scrollRectToVisible(
                                    delegado.tbl_contactos.getCellRect(filaVista, 0, true));
                            cargarContactoEnFormulario();
                        }
                    } else {
                        limpiarCampos();
                        mostrarNotificacion("Sin coincidencias para: " + texto, true);
                        if (mostrarAlertaNoExiste) {
                            JOptionPane.showMessageDialog(delegado, "El contacto no existe.");
                        }
                    }
                } catch (InterruptedException | ExecutionException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        busquedaSegundoPlano.execute();
    }

    // Notificación en tiempo real con colores según el tipo de mensaje (error o éxito)
    private void mostrarNotificacion(String mensaje, boolean esError) {
        final long miId = ++idUltimaNotificacion;
        new Thread(() -> {
            SwingUtilities.invokeLater(() -> {
                if (delegado.lbl_notificacion != null) {
                    delegado.lbl_notificacion.setForeground(
                            esError ? new Color(180, 0, 0) : new Color(0, 102, 51));
                    delegado.lbl_notificacion.setText(mensaje);
                }
            });
            try {
                Thread.sleep(3500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            SwingUtilities.invokeLater(() -> {
                if (delegado.lbl_notificacion != null && idUltimaNotificacion == miId) {
                    delegado.lbl_notificacion.setText(" ");
                }
            });
        }, "Hilo-Notificacion-GUI").start();
    }

    // Carga de contactos desde el archivo y actualización de la tabla y estadísticas
    private synchronized void cargarContactos() {
        try {
            contactos = dao.leerArchivo();
            delegado.modeloTabla.setRowCount(0);
            String idiomaActual = String.valueOf(delegado.cmb_idioma.getSelectedItem());
            String textoSi = "English".equals(idiomaActual) ? "Yes" : "Kichwa".equals(idiomaActual) ? "Arí" : "Sí";
            String textoNo = "Kichwa".equals(idiomaActual) ? "Mana" : "No";

            for (persona c : contactos) {
                Object[] fila = {
                    c.getNombre(),
                    c.getTelefono(),
                    c.getEmail(),
                    traducirCategoria(c.getCategoria()),
                    c.isFavorito() ? textoSi : textoNo
                };
                delegado.modeloTabla.addRow(fila);
            }
            actualizarEstadisticas();
        } catch (IOException e) {
            mostrarNotificacion("Error al cargar los contactos.", true);
            JOptionPane.showMessageDialog(delegado, "Error al cargar los contactos.");
        }
    }

    // Limpieza de los campos del formulario y reinicio de la selección en la tabla
    private void limpiarCampos() {
        delegado.txt_nombres.setText("");
        delegado.txt_telefono.setText("");
        delegado.txt_email.setText("");
        delegado.cmb_categoria.setSelectedIndex(0);
        delegado.chb_favorito.setSelected(false);
        delegado.tbl_contactos.clearSelection();
    }

    // Carga de los datos del contacto seleccionado en la tabla al formulario para su modificación
    private synchronized void cargarContactoEnFormulario() {
        int vistaFila = delegado.tbl_contactos.getSelectedRow();
        if (vistaFila >= 0) {
            int modeloFila = delegado.tbl_contactos.convertRowIndexToModel(vistaFila);
            persona p = contactos.get(modeloFila);
            delegado.txt_nombres.setText(p.getNombre());
            delegado.txt_telefono.setText(p.getTelefono());
            delegado.txt_email.setText(p.getEmail());
            delegado.cmb_categoria.setSelectedItem(traducirCategoria(p.getCategoria()));
            delegado.chb_favorito.setSelected(p.isFavorito());
        }
    }

    // Manejo de eventos de acción en los botones de la interfaz
    @Override
    public void actionPerformed(ActionEvent e) {
        Object fuente = e.getSource();
        if (fuente == delegado.btn_limpiar) {
            limpiarCampos();
        } else if (fuente == delegado.btn_add) {
            agregarContacto();
        } else if (fuente == delegado.btn_modificar) {
            modificarContacto();
        } else if (fuente == delegado.btn_eliminar) {
            eliminarContacto();
        } else if (fuente == delegado.btn_exportar) {
            exportarContactos();
        }
    }

    // Validación de campos del formulario antes de crear o modificar un contacto
    private boolean camposSonValidos(String nombres, String telefono, String email, String categoria) {
        String categoriaDefecto = delegado.textos.getString("cmb.categoria.defecto");
        if (nombres.isEmpty() || telefono.isEmpty() || email.isEmpty()
                || categoria.isEmpty() || categoria.equals(categoriaDefecto)) {
            mostrarNotificacion("Todos los campos son obligatorios.", true);
            JOptionPane.showMessageDialog(delegado, "Todos los campos son obligatorios.");
            return false;
        }
        return true;
    }

    // Obtención de los datos del formulario y creación de un objeto persona si los campos son válidos
    private persona obtenerDatosFormulario() {
        String nombres = delegado.txt_nombres.getText().trim();
        String telefono = delegado.txt_telefono.getText().trim();
        String email = delegado.txt_email.getText().trim();
        String categoria = delegado.cmb_categoria.getSelectedItem() != null
                ? delegado.cmb_categoria.getSelectedItem().toString()
                : "";
        boolean favorito = delegado.chb_favorito.isSelected();

        if (!camposSonValidos(nombres, telefono, email, categoria)) {
            return null;
        }
        return new persona(nombres, telefono, email, categoria, favorito);
    }

    // Agregar contacto con validación de duplicados en segundo plano y notificación en tiempo real
    private void agregarContacto() {
        persona nuevoContacto = obtenerDatosFormulario();
        if (nuevoContacto == null) {
            return;
        }
        delegado.btn_add.setEnabled(false);

        segundoPlano.submit(() -> {
            SwingUtilities.invokeLater(() -> {
                delegado.prg_carga.setVisible(true);
                delegado.prg_carga.setValue(0);
            });
            mostrarNotificacion("Validando si el contacto ya existe...", false);

            // Validacin de progreso de validación y escritura en segundo plano para no bloquear la interfaz de usuario
            try {
                // Paso 1: Validación en segundo plano
                for (int i = 0; i <= 40; i++) {
                    final int progreso = i;
                    SwingUtilities.invokeLater(() -> delegado.prg_carga.setValue(progreso));
                    Thread.sleep(8);
                }

                if (dao.existeContacto(nuevoContacto)) {
                    SwingUtilities.invokeLater(() -> {
                        delegado.prg_carga.setValue(0);
                        delegado.btn_add.setEnabled(true);
                        mostrarNotificacion("Contacto duplicado: ya se encuentra registrado.", true);
                        JOptionPane.showMessageDialog(delegado,
                                "El contacto ya está registrado (nombre, teléfono o email duplicado).",
                                "Validación de Duplicados",
                                JOptionPane.WARNING_MESSAGE);
                    });
                    return;
                }

                // Paso 2: Guardado si pasa la validación
                for (int i = 41; i <= 100; i++) {
                    final int progreso = i;
                    SwingUtilities.invokeLater(() -> delegado.prg_carga.setValue(progreso));
                    Thread.sleep(8);
                }

                // Guardado del contacto en el archivo de almacenamiento
                boolean guardado = dao.escribirArchivo(nuevoContacto);
                SwingUtilities.invokeLater(() -> {
                    if (guardado) {
                        refrescarVista();
                        mostrarNotificacion(delegado.textos.getString("msg.exito"), false);
                    } else {
                        mostrarNotificacion("Error al registrar el contacto.", true);
                        JOptionPane.showMessageDialog(delegado, "Error al registrar el contacto.");
                    }
                    delegado.prg_carga.setValue(0);
                    delegado.btn_add.setEnabled(true);
                });

            } catch (IOException | InterruptedException ex) {
                Thread.currentThread().interrupt();
                SwingUtilities.invokeLater(() -> {
                    delegado.prg_carga.setValue(0);
                    delegado.btn_add.setEnabled(true);
                    mostrarNotificacion("Error durante la validación del contacto.", true);
                });
            }
        });
    }

    // Modificar contacto con bloqueo por contacto para evitar ediciones concurrentes y notificación en tiempo real
    private void modificarContacto() {
        int vistaFila = delegado.tbl_contactos.getSelectedRow();
        if (vistaFila < 0) {
            mostrarNotificacion("Seleccione un contacto de la tabla para modificar.", true);
            JOptionPane.showMessageDialog(delegado, "Seleccione un contacto de la tabla para modificar.");
            return;
        }
        persona datosActualizados = obtenerDatosFormulario();
        if (datosActualizados == null) {
            return;
        }

        final int modeloFila = delegado.tbl_contactos.convertRowIndexToModel(vistaFila);
        final persona contactoOriginal;
        synchronized (this) {
            contactoOriginal = contactos.get(modeloFila);
        }

        // Clave única del recurso (contacto) para bloquear ediciones sobre el mismo registro
        final String claveRecurso = contactoOriginal.getEmail().toLowerCase() + "|" + contactoOriginal.getTelefono();
        final ReentrantLock lockContacto = bloqueoContactos.computeIfAbsent(claveRecurso, k -> new ReentrantLock());

        // Intento de adquirir el bloqueo para el contacto seleccionado, mostrando notificación si está siendo editado por otro proceso
        if (!lockContacto.tryLock()) {
            mostrarNotificacion("El contacto está siendo editado por otro proceso.", true);
            JOptionPane.showMessageDialog(delegado,
                    "Este contacto está bloqueado porque otro hilo/usuario lo está modificando actualmente.",
                    "Recurso Bloqueado",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Ejecución de la modificación en segundo plano para no bloquear la interfaz de usuario
        segundoPlano.submit(() -> {
            try {
                mostrarNotificacion("Modificando contacto de forma segura...", false);
                synchronized (this) {
                    contactos.set(modeloFila, datosActualizados);
                    dao.actualizarContactos(contactos);
                }
                SwingUtilities.invokeLater(() -> {
                    refrescarVista();
                    mostrarNotificacion("Contacto modificado exitosamente.", false);
                });
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    mostrarNotificacion("Error al actualizar el contacto.", true);
                    JOptionPane.showMessageDialog(delegado, "Error al actualizar el contacto.");
                });
            } finally {
                lockContacto.unlock();
            }
        });
    }

    // Exportar contactos a CSV en segundo plano con barra de progreso y notificación en tiempo real
    private void exportarContactos() {
        final List<persona> copiaExportar;
        synchronized (this) {
            copiaExportar = new ArrayList<>(contactos);
        }

        if (copiaExportar.isEmpty()) {
            mostrarNotificacion("No hay contactos para exportar.", true);
            return;
        }

        // Ejecución de la exportación en segundo plano para no bloquear la interfaz de usuario
        segundoPlano.submit(() -> {
            SwingUtilities.invokeLater(() -> {
                delegado.prg_carga.setVisible(true);
                delegado.prg_carga.setValue(0);
            });
            mostrarNotificacion("Exportando contactos a CSV...", false);

            for (int i = 0; i <= 100; i += 5) {
                final int progreso = i;
                SwingUtilities.invokeLater(() -> delegado.prg_carga.setValue(progreso));
                try {
                    Thread.sleep(15);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }

            // Llamada al método de exportación y manejo del resultado en la interfaz de usuario
            boolean exito = dao.exportarContactosCSV(copiaExportar);
            SwingUtilities.invokeLater(() -> {
                delegado.prg_carga.setValue(0);
                if (exito) {
                    mostrarNotificacion("Exportación completada: " + dao.getRutaExportacion(), false);
                } else {
                    mostrarNotificacion("Error al exportar los contactos.", true);
                }
            });
        });
    }

    // Eliminar contacto con confirmación y actualización de la vista y estadísticas
    private void eliminarContacto() {
        int vistaFila = delegado.tbl_contactos.getSelectedRow();
        if (vistaFila < 0) {
            mostrarNotificacion("Seleccione un contacto de la tabla para eliminar.", true);
            JOptionPane.showMessageDialog(delegado, "Seleccione un contacto de la tabla para eliminar.");
            return;
        }
        int confirmacion = JOptionPane.showConfirmDialog(
                delegado,
                "¿Seguro que desea eliminar este contacto?",
                "Confirmar Eliminación",
                JOptionPane.YES_NO_OPTION
        );
        if (confirmacion == JOptionPane.YES_OPTION) {
            int modeloFila = delegado.tbl_contactos.convertRowIndexToModel(vistaFila);
            synchronized (this) {
                contactos.remove(modeloFila);
                try {
                    dao.actualizarContactos(contactos);
                    refrescarVista();
                    mostrarNotificacion("Contacto eliminado correctamente.", false);
                } catch (IOException ex) {
                    mostrarNotificacion("Error al eliminar el contacto.", true);
                    JOptionPane.showMessageDialog(delegado, "Error al eliminar el contacto.");
                }
            }
        }
    }

    private void refrescarVista() {
        cargarContactos();
        limpiarCampos();
    }

    // Traducción de categorías para mostrar en la interfaz según el idioma seleccionado
    private String traducirCategoria(String categoriaGuardada) {
        if (categoriaGuardada == null) {
            return "";
        }
        switch (categoriaGuardada.toLowerCase()) {
            case "familia":
            case "family":
            case "ayllu":
                return delegado.textos.getString("cat.familia");
            case "amigos":
            case "friends":
            case "masiykuna":
                return delegado.textos.getString("cat.amigos");
            case "trabajo":
            case "work":
            case "llamkay":
                return delegado.textos.getString("cat.trabajo");
            default:
                return categoriaGuardada;
        }
    }

    // Actualización de estadísticas en la interfaz según la distribución de contactos por categoría
    private void actualizarEstadisticas() {
        if (contactos == null) {
            return;
        }
        delegado.lbl_totalContactos.setText("Total de contactos: " + contactos.size());
        String catFamilia = delegado.textos.getString("cat.familia");
        String catAmigos = delegado.textos.getString("cat.amigos");
        String catTrabajo = delegado.textos.getString("cat.trabajo");
        int fam = 0, ami = 0, tra = 0;

        for (persona c : contactos) {
            String cat = traducirCategoria(c.getCategoria());
            if (cat.equals(catFamilia)) {
                fam++;
            } else if (cat.equals(catAmigos)) {
                ami++;
            } else if (cat.equals(catTrabajo)) {
                tra++;
            }
        }
        String distribucion = String.format(
                "Distribución por Categoría:\n\n  %s: %d\n  %s: %d\n  %s: %d",
                catFamilia, fam, catAmigos, ami, catTrabajo, tra
        );
        delegado.txt_distribucionCategoria.setText(distribucion);
    }
}