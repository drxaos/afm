package com.github.drxaos.afm;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.attribute.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class CurrentDirectoryRestServlet extends HttpServlet {
    private static final Logger LOG = LoggerFactory.getLogger(CurrentDirectoryRestServlet.class);

    enum Mode {
        list, rename, copy, delete, savefile, editfile, addfolder, changepermissions, compress, extract
    }

    private String REPOSITORY_BASE_URL = "/";

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // Catch download requests
        // [$config.downloadFileUrl]?mode=download&preview=true&path=/public_html/image.jpg
        String mode = request.getParameter("mode");
        boolean preview = BooleanUtils.toBoolean(request.getParameter("preview"));
        String path = request.getParameter("path");

        File file = getFile(path);

        if (!file.isFile()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Resource Not Found");
            return;
        }

        // se imageName non ha estensione o non è immagine sballa! ;)
        // response.setHeader("Content-Type", getServletContext().getMimeType(imageName));
        response.setHeader("Content-Type", getServletContext().getMimeType(file.getName()));
        response.setHeader("Content-Length", String.valueOf(file.length()));
        response.setHeader("Content-Disposition", "inline; filename=\"" + file.getName() + "\"");

        FileInputStream input = null;
        BufferedOutputStream output = null;
        try {

            input = new FileInputStream(file);
            output = new BufferedOutputStream(response.getOutputStream());
            byte[] buffer = new byte[8192];
            for (int length = 0; (length = input.read(buffer)) > 0; ) {
                output.write(buffer, 0, length);
            }
        } catch (Throwable t) {
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException logOrIgnore) {
                }
            }
            if (input != null) {
                try {
                    input.close();
                } catch (IOException logOrIgnore) {
                }
            }
        }

    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            // if request contains multipart-form-data
            if (ServletFileUpload.isMultipartContent(request)) {
                uploadFile(request, response);
            }
            // all other post request has jspn params in body
            else {
                fileOperation(request, response);
            }
        } catch (Throwable t) {
            setError(t, response);
        }

    }

    private void setError(Throwable t, HttpServletResponse response) throws IOException {
        try {
            // { "result": { "success": false, "error": "message" } }
            JSONObject responseJsonObject = error(t.getMessage());
            response.setContentType("application/json");
            PrintWriter out = response.getWriter();
            out.print(responseJsonObject);
            out.flush();
        } catch (Throwable x) {
            response.sendError(HttpStatus.SC_INTERNAL_SERVER_ERROR, x.getMessage());
        }

    }

    private void uploadFile(HttpServletRequest request, HttpServletResponse response) throws ServletException {
        // URL: $config.uploadUrl, Method: POST, Content-Type: multipart/form-data
        // Unlimited file upload, each item will be enumerated as file-1, file-2, etc.
        // [$config.uploadUrl]?destination=/public_html/image.jpg&file-1={..}&file-2={...}
        try {
            String destination = null;
            Map<String, InputStream> files = new HashMap<String, InputStream>();

            List<FileItem> items = new ServletFileUpload(new DiskFileItemFactory()).parseRequest(request);
            for (FileItem item : items) {
                if (item.isFormField()) {
                    // Process regular form field (input type="text|radio|checkbox|etc", select, etc).
                    if ("destination".equals(item.getFieldName())) {
                        destination = item.getString();
                    }
                } else {
                    // Process form file field (input type="file").
                    files.put(item.getName(), item.getInputStream());
                }
            }
            if (files.size() == 0) {
                throw new Exception("file size  = 0");
            } else {
                for (Map.Entry<String, InputStream> fileEntry : files.entrySet()) {
                    File f = new File(getFile(destination), fileEntry.getKey());
                    if (!write(fileEntry.getValue(), f)) {
                        throw new Exception("write error");
                    }
                }
            }
        } catch (FileUploadException e) {
            throw new ServletException("Cannot parse multipart request: DiskFileItemFactory.parseRequest", e);
        } catch (IOException e) {
            throw new ServletException("Cannot parse multipart request: item.getInputStream", e);
        } catch (Exception e) {
            throw new ServletException("Cannot write file", e);
        }

    }

    private boolean write(InputStream inputStream, File f) {
        boolean ret = false;

        // http://www.mkyong.com/java/how-to-convert-inputstream-to-file-in-java/
        OutputStream outputStream = null;

        try {
            // write the inputStream to a FileOutputStream
            outputStream = new FileOutputStream(f);

            int read = 0;
            byte[] bytes = new byte[1024];

            while ((read = inputStream.read(bytes)) != -1) {
                outputStream.write(bytes, 0, read);
            }
            ret = true;

        } catch (IOException e) {
            LOG.error("", e);

        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    LOG.error("", e);
                }
            }
            if (outputStream != null) {
                try {
                    // outputStream.flush();
                    outputStream.close();
                } catch (IOException e) {
                    LOG.error("", e);
                }

            }
        }
        return ret;
    }

    private void fileOperation(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        JSONObject responseJsonObject = null;
        try {
            // legge il parametro json
            StringBuilder sb = new StringBuilder();
            BufferedReader br = request.getReader();
            String str;
            while ((str = br.readLine()) != null) {
                sb.append(str);
            }
            br.close();
            JSONObject jObj = new JSONObject(sb.toString());
            // legge mode e chiama il metodo aapropriato
            JSONObject params = jObj.getJSONObject("params");
            Mode mode = Mode.valueOf(params.getString("mode"));
            switch (mode) {
                case addfolder:
                    responseJsonObject = addFolder(params);
                    break;
                case changepermissions:
                    responseJsonObject = changePermissions(params);
                    break;
                case compress:
                    responseJsonObject = compress(params);
                    break;
                case copy:
                    responseJsonObject = copy(params);
                    break;
                case delete:
                    responseJsonObject = delete(params);
                    break;
                case editfile: // get content
                    responseJsonObject = editFile(params);
                    break;
                case savefile: // save content
                    responseJsonObject = saveFile(params);
                    break;
                case extract:
                    responseJsonObject = extract(params);
                    break;
                case list:
                    responseJsonObject = list(params);
                    break;
                case rename:
                    responseJsonObject = rename(params);
                    break;
                default:
                    throw new ServletException("not implemented");
            }
            if (responseJsonObject == null) {
                responseJsonObject = error("generic error : responseJsonObject is null");
            }
        } catch (Exception e) {
            responseJsonObject = error(e.getMessage());
        }
        response.setContentType("application/json");
        PrintWriter out = response.getWriter();
        out.print(responseJsonObject);
        out.flush();
    }

    private JSONObject list(JSONObject params) throws ServletException {
        try {
            boolean onlyFolders = params.getBoolean("onlyFolders");
            String path = params.getString("path");
            LOG.debug("list path: {} onlyFolders: {}", path, onlyFolders);

            File dir = getFile(path);
            File[] fileList = dir.listFiles();

            List<JSONObject> resultList = new ArrayList<JSONObject>();
            String DATE_FORMAT = "yyyy-MM-dd hh:mm:ss";
            SimpleDateFormat dt = new SimpleDateFormat(DATE_FORMAT);
            if (fileList != null) {
                // Calendar cal = Calendar.getInstance();
                for (File f : fileList) {
                    if (!f.exists() || (onlyFolders && !f.isDirectory())) {
                        continue;
                    }
                    BasicFileAttributes attrs = Files.readAttributes(f.toPath(), BasicFileAttributes.class);
                    JSONObject el = new JSONObject();
                    el.put("name", f.getName());
                    el.put("rights", getPermissions(f));
                    el.put("date", dt.format(new Date(attrs.lastModifiedTime().toMillis())));
                    el.put("size", f.length());
                    el.put("type", f.isFile() ? "file" : "dir");
                    resultList.add(el);
                }
            }

            return new JSONObject().put("result", resultList);
        } catch (Exception e) {
            LOG.error("list", e);
            return error(e.getMessage());
        }
    }

    private File getFile(String path) {
        //return new File(getServletContext().getRealPath(REPOSITORY_BASE_URL), path);
        return new File(REPOSITORY_BASE_URL, path);
    }

    private JSONObject rename(JSONObject params) throws ServletException {
        try {
            String path = params.getString("path");
            String newpath = params.getString("newPath");
            LOG.debug("rename from: {} to: {}", path, newpath);

            File srcFile = getFile(path);
            File destFile = getFile(newpath);
            if (srcFile.isFile()) {
                FileUtils.moveFile(srcFile, destFile);
            } else {
                FileUtils.moveDirectory(srcFile, destFile);
            }
            return success(params);
        } catch (Exception e) {
            LOG.error("rename", e);
            return error(e.getMessage());
        }
    }

    private JSONObject copy(JSONObject params) throws ServletException {
        try {
            String path = params.getString("path");
            String newpath = params.getString("newPath");
            LOG.debug("copy from: {} to: {}", path, newpath);
            File srcFile = getFile(path);
            File destFile = getFile(newpath);
            if (srcFile.isFile()) {
                FileUtils.copyFile(srcFile, destFile);
            } else {
                FileUtils.copyDirectory(srcFile, destFile);
            }
            return success(params);
        } catch (Exception e) {
            LOG.error("copy", e);
            return error(e.getMessage());
        }
    }

    private JSONObject delete(JSONObject params) throws ServletException {
        try {
            String path = params.getString("path");
            LOG.debug("delete {}", path);
            File srcFile = getFile(path);
            if (!FileUtils.deleteQuietly(srcFile)) {
                throw new Exception("Can't delete: " + srcFile.getAbsolutePath());
            }
            return success(params);
        } catch (Exception e) {
            LOG.error("delete", e);
            return error(e.getMessage());
        }
    }

    private JSONObject editFile(JSONObject params) throws ServletException {
        // get content
        try {
            String path = params.getString("path");
            LOG.debug("editFile path: {}", path);

            File srcFile = getFile(path);
            String content = FileUtils.readFileToString(srcFile);

            return new JSONObject().put("result", content);
        } catch (Exception e) {
            LOG.error("editFile", e);
            return error(e.getMessage());
        }
    }

    private JSONObject saveFile(JSONObject params) throws ServletException {
        // save content
        try {
            String path = params.getString("path");
            String content = params.getString("content");
            LOG.debug("saveFile path: {} content: isNotBlank {}, size {}", path, StringUtils.isNotBlank(content), content != null ? content.length() : 0);

            File srcFile = getFile(path);
            FileUtils.writeStringToFile(srcFile, content);

            return success(params);
        } catch (Exception e) {
            LOG.error("saveFile", e);
            return error(e.getMessage());
        }
    }

    private JSONObject addFolder(JSONObject params) throws ServletException {
        try {
            String path = params.getString("path");
            String name = params.getString("name");
            LOG.debug("addFolder path: {} name: {}", path, name);
            File newDir = new File(getFile(path), name);
            if (!newDir.mkdir()) {
                throw new Exception("Can't create directory: " + newDir.getAbsolutePath());
            }
            return success(params);
        } catch (Exception e) {
            LOG.error("addFolder", e);
            return error(e.getMessage());
        }
    }

    private JSONObject changePermissions(JSONObject params) throws ServletException {
        try {
            String path = params.getString("path");
            String perms = params.getString("perms"); // "653"
            String permsCode = params.getString("permsCode"); // "rw-r-x-wx"
            boolean recursive = params.getBoolean("recursive");
            LOG.debug("changepermissions path: {} perms: {} permsCode: {} recursive: {}", path, perms, permsCode, recursive);
            File f = getFile(path);
            setPermissions(f, permsCode, recursive);
            return success(params);
        } catch (Exception e) {
            LOG.error("changepermissions", e);
            return error(e.getMessage());
        }
    }

    private JSONObject compress(JSONObject params) throws ServletException {
        try {
            String path = params.getString("path"); // "/public_html/compressed.zip"
            String destination = params.getString("destination"); // "/public_html/backups"
            // FIXME parameters are right? the doc so...
            LOG.debug("compress path: {} destination: {}", path, destination);
            // TODO
            return error("not implemented");
            // return success(params);
        } catch (Exception e) {
            LOG.error("compress", e);
            return error(e.getMessage());
        }
    }

    private JSONObject extract(JSONObject params) throws ServletException {
        try {
            String path = params.getString("path"); // "/public_html/compressed.zip"
            String destination = params.getString("destination"); // "/public_html/extracted-files"
            String sourceFile = params.getString("sourceFile"); // /public_html/compressed.zip"
            // FIXME parameters are right? the doc so...
            LOG.debug("extract path: {} destination: {} sourceFile: {}", path, destination, sourceFile);
            // TODO
            return error("not implemented");
        } catch (Exception e) {
            LOG.error("extract", e);
            return error(e.getMessage());
        }
    }

    private String getPermissions(File f) throws IOException {
        // http://www.programcreek.com/java-api-examples/index.php?api=java.nio.file.attribute.PosixFileAttributes
        PosixFileAttributeView fileAttributeView = Files.getFileAttributeView(f.toPath(), PosixFileAttributeView.class);
        PosixFileAttributes readAttributes = fileAttributeView.readAttributes();
        Set<PosixFilePermission> permissions = readAttributes.permissions();
        return PosixFilePermissions.toString(permissions);
    }

    private String setPermissions(File file, String permsCode, boolean recursive) throws IOException {
        // http://www.programcreek.com/java-api-examples/index.php?api=java.nio.file.attribute.PosixFileAttributes
        PosixFileAttributeView fileAttributeView = Files.getFileAttributeView(file.toPath(), PosixFileAttributeView.class);
        fileAttributeView.setPermissions(PosixFilePermissions.fromString(permsCode));
        if (file.isDirectory() && recursive && file.listFiles() != null) {
            for (File f : file.listFiles()) {
                setPermissions(f, permsCode, recursive);
            }
        }
        return permsCode;
    }

    private JSONObject error(String msg) throws ServletException {
        try {
            // { "result": { "success": false, "error": "msg" } }
            JSONObject result = new JSONObject();
            result.put("success", false);
            result.put("error", msg);
            return new JSONObject().put("result", result);
        } catch (JSONException e) {
            throw new ServletException(e);
        }
    }

    private JSONObject success(JSONObject params) throws ServletException {
        try {
            // { "result": { "success": true, "error": null } }
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("error", (Object) null);
            return new JSONObject().put("result", result);
        } catch (JSONException e) {
            throw new ServletException(e);
        }
    }

}
