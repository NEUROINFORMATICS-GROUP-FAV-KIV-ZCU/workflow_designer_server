package cz.zcu.kiv.server;


import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.glassfish.jersey.media.multipart.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

@Path("/workflow")
public class Workflow {
    private static Log logger = LogFactory.getLog(Workflow.class);

    /** The path to the folder where we want to store the uploaded files */
    private static final String UPLOAD_FOLDER = "uploadedFiles/";
    private static final String GENERATED_FILES_FOLDER = "generatedFiles/";

    public Workflow() {
    }

    @Context
    private UriInfo context;

    @GET
    @Path("/test")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String test() {

        return "works";
    }


    @GET
    @Path("/file/{filename}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response test(@PathParam("filename")String filename) {

        File file = new File(GENERATED_FILES_FOLDER+filename);
        if(file.exists()){
            return Response.status(200).entity(file).build();
        }
        else{
            return Response.status(404)
                    .entity("File not found!").build();
        }

    }

    /**
     * Returns text response to caller containing Blocks representation of parsed classes
     *
     * @return error response in case of missing parameters an internal
     *         exception or success response if file has been stored
     *         successfully
     */
    @POST
    @Path("/uploadJar")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadJar(
            @FormDataParam("file") File file,
            @FormDataParam("file") FormDataContentDisposition fileDetail,
            @FormDataParam("package") String packageName) {
        // check if all form parameters are provided
        if (file == null || packageName == null )
            return Response.status(400).entity("Invalid form data").build();
        // create our destination folder, if it not exists
        try {
            createFolderIfNotExists(UPLOAD_FOLDER);
        } catch (SecurityException se) {
            return Response.status(500)
                    .entity("Can not create destination folder on server")
                    .build();
        }
        new File(UPLOAD_FOLDER).mkdirs();

        String uploadedFileLocation = UPLOAD_FOLDER + fileDetail.getFileName();
        File outputFile;
        try {
            FileInputStream fos=new FileInputStream(file);
            outputFile = saveToFile(fos, uploadedFileLocation);
        } catch (IOException e) {
            e.printStackTrace();
            return Response.status(500).entity("Can not save file").build();
        }
        JSONArray result = null;
        try{
            URLClassLoader child = new URLClassLoader(new URL[]{outputFile.toURL()}, this.getClass().getClassLoader());

            JarFile jarFile = new JarFile(outputFile);
            Enumeration e = jarFile.entries();


            while (e.hasMoreElements()) {
                JarEntry je = (JarEntry) e.nextElement();
                if(je.isDirectory() || !je.getName().endsWith(".class")){
                    continue;
                }
                String className = je.getName().substring(0,je.getName().length()-6);
                className = className.replace('/', '.');
                if(className.startsWith(packageName)){
                     child.loadClass(className);
                }

            }

            Class classToLoad = Class.forName("cz.zcu.kiv.WorkflowDesigner.Workflow", true, child);
            Constructor<?> ctor=classToLoad.getConstructor(String.class,ClassLoader.class);

            Method method = classToLoad.getDeclaredMethod("initializeBlocks");
            method.setAccessible(true);
            Object instance = ctor.newInstance(packageName,child);
            result = (JSONArray)method.invoke(instance);
        }
        catch(Exception e){
            e.printStackTrace();
            Response.status(200)
                    .entity("Execution failed with " + e.getMessage()).build();
        }
        return Response.status(200)
                .entity(result.toString(4)).build();
    }

    /**
     * Returns text response to indicate execution success
     *
     * @return error response in case of missing parameters an internal
     *         exception or success response if file has been stored
     *         successfully
     */
    @POST
    @Path("/executeJar")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response executeJar(
            @FormDataParam("file") File file,
            @FormDataParam("file") FormDataContentDisposition fileDetail,
            @FormDataParam("package") String packageName,
            @FormDataParam("workflow") String workflow) {


        JSONObject workflow_object = null;
        // check if all form parameters are provided
        if (file == null || packageName == null || workflow == null)
            return Response.status(400).entity("Invalid form data").build();

        try {
             workflow_object = new JSONObject(workflow);

        }
        catch (JSONException e){
            e.printStackTrace();
            return Response.status(400).entity("Invalid workflow JSON").build();

        }
        // create our destination folder, if it not exists
        try {
            createFolderIfNotExists(UPLOAD_FOLDER);
            createFolderIfNotExists(GENERATED_FILES_FOLDER);
        } catch (SecurityException se) {
            return Response.status(500)
                    .entity("Can not create destination folder on server")
                    .build();
        }
        String uploadedFileLocation = UPLOAD_FOLDER + fileDetail.getFileName();
        File outputFile;
        try {
            FileInputStream fos=new FileInputStream(file);
            outputFile = saveToFile(fos, uploadedFileLocation);
        } catch (IOException e) {
            e.printStackTrace();
            return Response.status(500).entity("Can not find file").build();
        }
        String result = null;
        try{

            Process ps=Runtime.getRuntime().exec(new String[]{"java","-cp",outputFile.getAbsolutePath(),"cz.zcu.kiv.WorkflowDesigner.Workflow",packageName,workflow_object.toString(),"output.txt",new File(GENERATED_FILES_FOLDER).getAbsolutePath()});
            ps.waitFor();
            InputStream is=ps.getErrorStream();
            byte b[]=new byte[is.available()];
            is.read(b,0,b.length);
            logger.fatal(new String(b));

            is=ps.getInputStream();
            b=new byte[is.available()];
            is.read(b,0,b.length);
            logger.info(new String(b));
            result =FileUtils.readFileToString(new File("output.txt"),Charset.defaultCharset());
        }
        catch(Exception e){
            e.printStackTrace();
            Response.status(500)
                    .entity("Execution failed with " + e.getMessage() ).build();
        }
        return Response.status(200)
                .entity(result).build();
    }

    /**
     * Returns text response to caller containing Blocks representation of parsed classes
     *
     * @return error response in case of missing parameters an internal
     *         exception or success response if file has been stored
     *         successfully
     */
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_PLAIN)
    public Response uploadJar(
            final FormDataMultiPart body,
            @FormDataParam("package") String packageName)  {
        // check if all form parameters are provided
        if (body == null || packageName == null )
            return Response.status(400).entity("Invalid form data").build();
        // create our destination folder, if it not exists
        try {
            createFolderIfNotExists(UPLOAD_FOLDER);
        } catch (SecurityException se) {
            se.printStackTrace();
            return Response.status(500)
                    .entity("Can not create destination folder on server")
                    .build();
        }


        ClassLoader child;
        try {
            child = initializeClassLoader(packageName,body);
        } catch (IOException e) {
            e.printStackTrace();
            return Response.status(500)
                    .entity("Can not read destination folder on server")
                    .build();
        }


        JSONArray result=new JSONArray();

        try{


            Class classToLoad = Class.forName("cz.zcu.kiv.WorkflowDesigner.Workflow", true, child);
            Constructor<?> ctor=classToLoad.getConstructor(String.class,ClassLoader.class);

            Method method = classToLoad.getDeclaredMethod("initializeBlocks");
            method.setAccessible(true);
            Object instance = ctor.newInstance(packageName,child);
            result = (JSONArray)method.invoke(instance);
        }
        catch(Exception e){
            e.printStackTrace();
            Response.status(200)
                    .entity("Execution failed with " + e.getMessage()).build();
        }
        return Response.status(200)
                .entity(result.toString(4)).build();
    }




    /**
     * Returns text response to indicate execution success
     *
     * @return error response in case of missing parameters an internal
     *         exception or success response if file has been stored
     *         successfully
     */
    @POST
    @Path("/execute")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_PLAIN)
    public Response execute(
            final FormDataMultiPart formData,
            @FormDataParam("package") String packageName,
            @FormDataParam("workflow") String workflow)  {

        if (formData == null || packageName == null || workflow == null)
            return Response.status(400).entity("Invalid form data").build();

        JSONObject workflowObject = null;
        // check if all form parameters are provided


        try {
            workflowObject = new JSONObject(workflow);

        }
        catch (JSONException e){
            e.printStackTrace();
            return Response.status(400).entity("Invalid workflow JSON").build();

        }
        // create our destination folder, if it not exists
        try {
            createFolderIfNotExists(UPLOAD_FOLDER);
            createFolderIfNotExists(GENERATED_FILES_FOLDER);
        } catch (SecurityException se) {
            return Response.status(500)
                    .entity("Can not create destination folder on server")
                    .build();
        }

        ClassLoader child;
        try {
            child = initializeClassLoader(packageName,formData);
        } catch (IOException e) {
            return Response.status(500)
                    .entity("Can not read destination folder on server")
                    .build();
        }

        JSONArray result = null;

        try{
            Class classToLoad = Class.forName("cz.zcu.kiv.WorkflowDesigner.Workflow", true, child);
            Thread.currentThread().setContextClassLoader(child);

            Constructor<?> ctor=classToLoad.getConstructor( String.class, ClassLoader.class);
            Method method = classToLoad.getDeclaredMethod("execute",JSONObject.class,String.class);
            Object instance = ctor.newInstance(packageName,child);
            result = (JSONArray)method.invoke(instance,workflowObject,GENERATED_FILES_FOLDER);
        }
        catch(Exception e1){
            e1.printStackTrace();
            Response.status(500)
                    .entity("Execution failed with " + e1.getMessage() ).build();
        }
        if(result!=null)
        return Response.status(200)
                .entity(result.toString(4)).build();
        else{
            return Response.status(400).entity("No output").build();
        }
    }

    /**
     * Utility method to save InputStream data to target location/file
     *  @param inStream
     *            - InputStream to be saved
     * @param target
     */
    private File saveToFile(InputStream inStream, String target)
            throws IOException {
        OutputStream out = null;
        int read = 0;
        byte[] bytes = new byte[1024];
        File file = new File(target);
        file.delete();
            out = new FileOutputStream(file);
            while ((read = inStream.read(bytes)) != -1) {
                out.write(bytes, 0, read);
            }
            out.flush();
            out.close();
            return file;

    }

    /**
     * Creates a folder to desired location if it not already exists
     *
     * @param dirName
     *            - full path to the folder
     * @throws SecurityException
     *             - in case you don't have permission to create the folder
     */
    private void createFolderIfNotExists(String dirName)
            throws SecurityException {
        File theDir = new File(dirName);
        if (!theDir.exists()) {
            theDir.mkdir();
        }
    }


    private ClassLoader initializeClassLoader(String packageName,FormDataMultiPart multiPart) throws IOException {

        Map<String, List<FormDataBodyPart>> map = multiPart.getFields();

        ArrayList<FormDataBodyPart>fileParts =new ArrayList<>();
        for (Map.Entry<String, List<FormDataBodyPart>> entry : map.entrySet()) {

            for (FormDataBodyPart part : entry.getValue()) {
                if(part.getName().equals("file")){
                    fileParts.add(part);
                }

            }
        }

        int files = fileParts.size();
        URL[]urls=new URL[files];
        File[]outputFiles=new File[files];


        for(int i=0; i<files;i++){
            BodyPart part=fileParts.get(i);
            InputStream is = part.getEntityAs(InputStream.class);
            ContentDisposition meta = part.getContentDisposition();

            String uploadedFileLocation = UPLOAD_FOLDER + meta.getFileName();
                outputFiles[i] = saveToFile(is, uploadedFileLocation);
                urls[i]=outputFiles[i].toURL();
        }
        URLClassLoader child = new URLClassLoader(urls, this.getClass().getClassLoader());


        for(int i=0;i<files;i++){

            JarFile jarFile = new JarFile(outputFiles[i]);
            Enumeration e = jarFile.entries();

            while (e.hasMoreElements()) {
                JarEntry je = (JarEntry) e.nextElement();
                if(je.isDirectory() || !je.getName().endsWith(".class")||(packageName!=null&&!je.getName().startsWith(packageName))){
                    continue;
                }
                String className = je.getName().substring(0,je.getName().length()-6);
                className = className.replace('/', '.');
                try {
                    child.loadClass(className);
                }
                catch (Exception|Error e1){
                    logger.error(e1.getMessage());
                }
            }

        }
        Thread.currentThread().setContextClassLoader(child);
        return child;
    }
    
}
