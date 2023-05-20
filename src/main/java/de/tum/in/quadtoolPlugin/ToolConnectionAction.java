package de.tum.in.quadtoolPlugin;

import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ToolConnectionAction extends AnAction {
    static File path=PluginManager.getPlugin(PluginId.getId("de.tum.in.QuADTool_Plugin")).getPath();
    static File tool=new File(path,"quadtool.jar");
    static ExecutionOption runOption;
    static String command="";
    enum ExecutionOption{
        COMMAND,CLASSLOADER
    }
    private static Process current=null;
    static {
        //TODO Strings
        //Download Tool if it does not exist;
        fetchTool();
        //Check which options work
        checkExecution();
    }
    private static boolean loading=false;
    static void fetchTool(){
        if(!tool.exists()&&!loading) {
            loading=true;
            ExecutorService background = Executors.newSingleThreadExecutor();
            background.execute(() -> {
                InputStream in = null;
                try {
                    in = new URL("https://gitlab.com/live-lab/software/add-tool/-/raw/main/release/latest.jar").openStream();
                    Files.copy(in, tool.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    loading=false;
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
            background.shutdown();
        }
    }
    static void checkExecution(){
        //1: check if java is directly available
        if(existsCommand("where java")||existsCommand("which java")){
            command="java";
            runOption=ExecutionOption.COMMAND;
            return;
        }
        //2: try using intelliJ jre
        //path is of type IntelliJ/plugins/Quadtool so this should return IntelliJ
        //Applies for linux/windows
        File jbr=new File(path.getParentFile().getParentFile(),"jbr/bin");
        if(new File(jbr,"java.exe").exists()){
            command=String.format("\"%s\"",new File(jbr,"java.exe"));
            runOption=ExecutionOption.COMMAND;
            return;
        }
        if(new File(jbr,"java").exists()){
            command=String.format("\"%s\"",new File(jbr,"java"));
            runOption=ExecutionOption.COMMAND;
            return;
        }
        //3: use running classloader as backup
        runOption=ExecutionOption.CLASSLOADER;
    }
    public static boolean existsCommand(String command){
        try {
            Process whereJava = Runtime.getRuntime().exec(command);
            String output="";
            while(whereJava.isAlive()||whereJava.getInputStream().available()>0){
                byte[] b=new byte[whereJava.getInputStream().available()];
                whereJava.getInputStream().read(b);
                output+=new String(b);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                }
            }
            return !output.isEmpty()&&new File(output.trim()).exists();
        } catch (IOException e) {
            return false;
        }
    }
    @Override
    public void actionPerformed(AnActionEvent e) {
        if(!tool.exists()){
            Messages.showInfoMessage("Loading tool failed.\nWill try to download again.\nTry again later.", "Dependency missing");
            fetchTool();
            return;
        }
        switch (runOption){
            case COMMAND:
                String arg="";
                if(e.getProject()!=null&&FileEditorManager.getInstance(e.getProject())!=null){
                    VirtualFile files[]=FileEditorManager.getInstance(e.getProject()).getSelectedFiles();
                    arg=Arrays.stream(files).map(f->f.getPath()).collect(Collectors.joining("\" \""," \"","\""));
                }
                if(current!=null&&current.isAlive()){
                    try {
                        OutputStream stream=current.getOutputStream();
                        stream.write((arg).getBytes());
                        stream.flush();
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }else{
                    try {
                        String fullCommand=command+" -jar \""+tool.getAbsolutePath()+"\" -e"+arg;
                        current=Runtime.getRuntime().exec(fullCommand);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }
                break;
            case CLASSLOADER:
                if(true){
                    Messages.showInfoMessage("Loading jre for plugin failed. Please check java installation.","Startup failed.");
                    return;
                }
                // Option to run file in the same class loader. This creates a nasty clinch of lookandFeel keys
                //This is the last resort
                /*List<String> toLoad=new ArrayList<>();
                try {
                    //LOAD ALL CLASSES
                    java.util.jar.JarFile jar = new java.util.jar.JarFile(tool);

                    java.util.Enumeration enumEntries = jar.entries();
                    while (enumEntries.hasMoreElements()) {
                        java.util.jar.JarEntry file = (java.util.jar.JarEntry) enumEntries.nextElement();
                        if (!file.toString().endsWith(".class")) {
                            continue;
                        }
                        String path=file.toString();
                        if(path.contains("$")){
                            path=path.substring(0,path.indexOf('$'));
                        }else{
                            path=path.substring(0,path.indexOf('.'));
                        }
                        path=path.replaceAll("/",".");
                        toLoad.add(path);
                    }
                    jar.close();
                }catch (Exception ex){
                    ex.printStackTrace();
                }*/
                //END LOAD CLASSES
                try (URLClassLoader child = new URLClassLoader(new URL[]{tool.toURI().toURL()}, this.getClass().getClassLoader());){
                    String []args=new String[0];
                    if(e.getProject()!=null&&FileEditorManager.getInstance(e.getProject())!=null){
                        VirtualFile files[]=FileEditorManager.getInstance(e.getProject()).getSelectedFiles();
                        args=Arrays.stream(files).map(f->f.getPath()).toArray(String[]::new);
                    }
                    /*toLoad.forEach(s-> {
                        try {
                            child.loadClass(s);
                        } catch (ClassNotFoundException|java.lang.NoClassDefFoundError ex) {
                        }
                    });*/
                    final Class classToLoad = child.loadClass("addtool.scripts.MainEditor");
                    //Class.forName("addtool.scripts.MainEditor", true, child);
                    final Method method = classToLoad.getDeclaredMethod("loadFiles", String[].class);
                    final Method methodExit = classToLoad.getDeclaredMethod("setExitOnClose", boolean.class);
                    method.invoke(null, new Object[]{args});
                    methodExit.invoke(null, new Object[]{false});
                } catch (IOException|ClassNotFoundException|NoSuchMethodException|InvocationTargetException|IllegalAccessException ex) {
                    ex.printStackTrace();
                    Messages.showInfoMessage(ex.getLocalizedMessage(), ex.getClass().toString());
                }
                break;
        }
    }

}