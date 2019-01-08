import java.io.* ;
import java.net.* ;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.* ;

public final class WebServer
{
    static Hashtable<String, String> mindtypes;

    public static void main(String argv[]) throws Exception
    {
        // Set the port number.
        int port = 6789;

        // Establish the listen socket.
        ServerSocket serverSocket = new ServerSocket(port);

        // check for command line argument -mime
        if(argv.length > 1)
            if(argv[0].equals("-mime"))
                // copy mindtypes from file into a hashtable
                mindtypes = makeMindtypesTable(Paths.get(argv[1]));

        // Process HTTP service requests in an infinite loop.
        while (true) {
            // Listen for a TCP connection request.
            Socket clientSocket = serverSocket.accept();

            // Construct an object to process the HTTP request message.
            HttpRequest request = new HttpRequest(clientSocket);

            // Create a new thread to process the request.
            Thread thread = new Thread(request);

            // Start the thread.
            thread.start();

        }
    }

     static private Hashtable<String, String> makeMindtypesTable(Path path) {

        Hashtable<String, String> hashtable = new Hashtable<>();
        try {
            //store each line from the document in a list
            List<String> l = Files.readAllLines(path);

            //go through the document line by line and translate it into a hashtable
            // with the first element as value and the following as keys to that value
            for (String s : l) {
                if (!s.isEmpty() && !s.startsWith("#")) {
                    String[] words = s.split("\\s");
                    for (String w : words)
                        if (!w.equals(words[0]))
                            hashtable.put(w, words[0]);
                }
            }
        } catch (IOException e) {
            if (Files.exists(path))
                System.err.println(e + "\nDie Datei " + path + " konnte nicht gelesen werden.");

        }

        return hashtable;

    }

}

final class HttpRequest implements Runnable {

    private static InetAddress clientIP;
    private static String userAgent;
    private static int contentLength = -1;

    private final static String CRLF = "\r\n";
    private Socket socket;

    // Constructor
    public HttpRequest(Socket socket) throws Exception {
        this.socket = socket;
        clientIP = socket.getInetAddress(); // get and store client IP-address
    }

    // Implement the run() method of the Runnable interface.
    @Override
    public void run() {
        try {
            processHttpRequest();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processHttpRequest() throws Exception {
        // Get a reference to the socket's input and output streams.
        InputStream is = socket.getInputStream();
        DataOutputStream os = new DataOutputStream(socket.getOutputStream());

        // Set up input stream filters.
        InputStreamReader inputStreamReader = new InputStreamReader(is);
        BufferedReader br = new BufferedReader(inputStreamReader);

        // Get the request line of the HTTP request message.
        String requestLine = br.readLine();

        // Display the request line.
        System.out.println();
        System.out.println(requestLine);

        // Get and display the header lines.
        String headerLine = null;
        while ((headerLine = br.readLine()).length() != 0) {
            System.out.println(headerLine);
            // store User-Agent line for output in case of 404
            if (headerLine.startsWith("User-Agent"))
                userAgent = headerLine;
            // read given content length (if existing)
            if(headerLine.startsWith("Content-Length"))
                contentLength = Integer.parseInt(headerLine.substring(16));
        }

        // Extract the filename from the request line.
        StringTokenizer tokens = new StringTokenizer(requestLine);
        tokens.nextToken();  // skip over the method, which should be "GET"
        String fileName = tokens.nextToken();

        // Prepend a "." so that file request is within the current directory.
        fileName = "." + fileName;

        // Open the requested file.
        FileInputStream fis = null;
        boolean fileExists = true;
        try {
            fis = new FileInputStream(fileName);
        } catch (FileNotFoundException e) {
            fileExists = false;
        }

        // Construct the response message.
        String statusLine = null;
        String contentTypeLine = null;
        String entityBody = null;

        // assume a valid request
        boolean badRequest = false;
        // check if the request method is implemented (GET, HEAD or POST)
        boolean notImplemented = !requestLine.startsWith("GET") && !requestLine.startsWith("HEAD") &&
                !requestLine.startsWith("POST");
        // return 204 No Content for every POST request that is not empty
        boolean noContent = fileExists && requestLine.startsWith("POST") && contentLength != -1;
        // assume that the file needs not to be sent (due to error return statement or HEAD request)
        boolean sendFile = false;

        // check if POST request is valid (if given content length and actual content length accord)
        // if not, handle as a Bad Request
        if(requestLine.startsWith("POST") && contentLength > -1){
            try {
                char[] content = new char[contentLength+1];
                int actualContentLength = br.read(content);
                if(actualContentLength != contentLength)
                    throw new Exception();

            } catch (Exception e){
                badRequest = true;

            }
        }


        // Request handling
        if(notImplemented){

            statusLine = "HTTP/1.0 501 Not Implemented" + CRLF;
            contentTypeLine = "Content-type: text/html" + CRLF;
            entityBody = "<HTML>" +
                    "<HEAD><TITLE>Not Implemented</TITLE></HEAD>" +
                    "<BODY>Not Implemented</BODY></HTML>";

        } else if(badRequest) {

            statusLine = "HTTP/1.0 400 Bad Request" + CRLF;
            contentTypeLine = "Content-type: text/html" + CRLF;
            entityBody = "<HTML>" +
                    "<HEAD><TITLE>Bad Request</TITLE></HEAD>" +
                    "<BODY>Bad Request</BODY></HTML>";

        } else if(noContent) {

            statusLine = "HTTP/1.0 204 No Content" + CRLF;

        } else if(fileExists) {

            statusLine = "HTTP/1.0 200 OK" + CRLF;
            contentTypeLine = "Content-type: " +
                    contentType(fileName) + CRLF;
            sendFile = !requestLine.startsWith("HEAD");

        } else {

            statusLine = "HTTP/1.0 404 Not Found" + CRLF;
            contentTypeLine = "Content-type: text/html" + CRLF;
            entityBody = "<HTML>" +
                    "<HEAD><TITLE>Not Found</TITLE></HEAD>" +
                    "<BODY>" + "Not Found<br> Client-IP: " + clientIP +
                    "<br>" + userAgent + "</BODY></HTML>";

        }


        // Send the status line.
        os.writeBytes(statusLine);

        // Send the content type line.
        if(contentTypeLine != null)
            os.writeBytes(contentTypeLine);

        // Send a blank line to indicate the end of the header lines.
        os.writeBytes(CRLF);

        // Send the entity body.

        if(entityBody != null) {
            os.writeBytes(entityBody);
        }
        if (fileExists) {
            if(sendFile)
                sendBytes(fis, os);
            fis.close();

        }


        // Close streams and socket.
        os.close();
        br.close();
        socket.close();

    }

    private static void sendBytes(FileInputStream fis, OutputStream os) throws Exception {
        // Construct a 1K buffer to hold bytes on their way to the socket.
        byte[] buffer = new byte[1024];
        int bytes = 0;

        // Copy requested file into the socket's output stream.
        while ((bytes = fis.read(buffer)) != -1) {
            os.write(buffer, 0, bytes);
        }
    }

    private static String contentType(String fileName) {
        String suffix = fileName.substring(1 + fileName.lastIndexOf("."));
        if (WebServer.mindtypes.containsKey(suffix))
            return WebServer.mindtypes.get(suffix);

        return "application/octet-stream";
    }
}