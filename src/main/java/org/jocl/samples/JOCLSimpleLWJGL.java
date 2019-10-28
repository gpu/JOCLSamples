/*
 * JOCL - Java bindings for OpenCL
 * 
 * Copyright 2009-2019 Marco Hutter - http://www.jocl.org/
 */
package org.jocl.samples;

import static org.jocl.CL.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.*;
import java.util.Arrays;

import javax.swing.*;

import org.jocl.*;
import org.lwjgl.*;
import org.lwjgl.opengl.*;



/**
 * A small example demonstrating the JOCL/LWJGL interoperability,
 * using the "simpleGL.cl" kernel from the NVIDIA "oclSimpleGL"
 * example. This example is intended to be used with LWJGL 2.6, and
 * uses only the OpenGL 3.2 core profile and GLSL 1.5 
 */
public class JOCLSimpleLWJGL
{
    /**
     * Entry point for this sample.
     * 
     * @param args not used
     */
    public static void main(String args[])
    {
        SwingUtilities.invokeLater(new Runnable()
        {
            public void run()
            {
                new JOCLSimpleLWJGL();
            }
        });
    }

    /**
     * Compile-time flag which indicates whether the real OpenCL/OpenGL 
     * interoperation should be used. If this flag is 'true', then the 
     * buffers should be shared between OpenCL and OpenGL. If it is 
     * 'false', then the buffer contents will be copied via the host.
     */
    private static final boolean GL_INTEROP = false;

    /**
     * The source code for the vertex shader
     */
    private static String vertexShaderSource = 
        "#version 150 core" + "\n" +
        "in  vec4 inVertex;" + "\n" +
        "in  vec3 inColor;" + "\n" +
        "uniform mat4 modelviewMatrix;" + "\n" +
        "uniform mat4 projectionMatrix;" + "\n" +
        "void main(void)" + "\n" +
        "{" + "\n" +
        "    gl_Position = " + "\n" +
        "        projectionMatrix * modelviewMatrix * inVertex;" + "\n" +
        "}";
    
    /**
     * The source code for the fragment shader
     */
    private static String fragmentShaderSource =
        "#version 150 core" + "\n" +
        "out vec4 outColor;" + "\n" +
        "void main(void)" + "\n" +
        "{" + "\n" +
        "    outColor = vec4(1.0,0.0,0.0,1.0);" + "\n" +
        "}";
    
    
    /**
     * The width segments of the mesh to be displayed.
     */
    private static final int meshWidth = 8 * 64;

    /**
     * The height segments of the mesh to be displayed
     */
    private static final int meshHeight = 8 * 64;

    /**
     * The LWJGL canvas
     */
    private AWTGLCanvas glComponent;
    
    /**
     * The current animation state of the mesh
     */
    private float animationState = 0.0f;

    /**
     * The vertex array object (required as of GL3)
     */
    private int vertexArrayObject;
    
    /**
     * The VBO identifier
     */
    private int vertexBufferObject;

    /**
     * The cl_mem that has the contents of the VBO,
     * namely the vertex positions
     */
    private cl_mem vboMem;

    /**
     * The currently mapped VBO data buffer
     */
    private ByteBuffer mappedBuffer; 
    
    /**
     * The OpenCL context
     */
    private cl_context context;

    /**
     * The OpenCL command queue
     */
    private cl_command_queue commandQueue;

    /**
     * The OpenCL kernel
     */
    private cl_kernel kernel;

    /**
     * Whether the computation should be performed with JOCL or
     * with Java. May be toggled by pressing the 't' key
     */
    private boolean useJOCL = true;

    /**
     * A flag indicating whether the VBO and the VBO memory object
     * have to be re-initialized due to a switch between Java and 
     * JOCL computing mode
     * To do: This should not be necessary. Find out why leaving  
     * this out results in an OUT_OF_HOST_MEMORY error.
     */
    private boolean reInitVBOData = true;
    
    /**
     * The ID of the OpenGL shader program
     */
    private int shaderProgramID;
    
    /**
     * The translation in X-direction
     */
    private float translationX = 0;

    /**
     * The translation in Y-direction
     */
    private float translationY = 0;

    /**
     * The translation in Z-direction
     */
    private float translationZ = -4;

    /**
     * The rotation about the X-axis, in degrees
     */
    private float rotationX = 40;

    /**
     * The rotation about the Y-axis, in degrees
     */
    private float rotationY = 30;

    /**
     * The current projection matrix
     */
    float projectionMatrix[] = new float[16];

    /**
     * The projection matrix buffer
     */
    private FloatBuffer projectionMatrixBuffer = createFloatBuffer(16);
    
    /**
     * The current modelview matrix
     */
    float modelviewMatrix[] = new float[16];
    
    /**
     * The modelview matrix buffer
     */
    private FloatBuffer modelviewMatrixBuffer = createFloatBuffer(16);
    
    /**
     * Step counter for FPS computation
     */
    private int step = 0;
    
    /**
     * Timestamp for FPS computation
     */
    private long prevTimeNS = -1;
    
    /**
     * The main frame of the application
     */
    private Frame frame;
    
    /**
     * Temporary buffer for ID creation
     */
    private static IntBuffer tempIntBuffer = createIntBuffer(1);
    
    /**
     * Inner class encapsulating the MouseMotionListener and
     * MouseWheelListener for the interaction
     */
    class MouseControl implements MouseMotionListener, MouseWheelListener
    {
        private Point previousMousePosition = new Point();

        @Override
        public void mouseDragged(MouseEvent e)
        {
            int dx = e.getX() - previousMousePosition.x;
            int dy = e.getY() - previousMousePosition.y;

            // If the left button is held down, move the object
            if ((e.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) == 
                MouseEvent.BUTTON1_DOWN_MASK)
            {
                translationX += dx / 100.0f;
                translationY -= dy / 100.0f;
            }

            // If the right button is held down, rotate the object
            else if ((e.getModifiersEx() & MouseEvent.BUTTON3_DOWN_MASK) == 
                MouseEvent.BUTTON3_DOWN_MASK)
            {
                rotationX += dy;
                rotationY += dx;
            }
            previousMousePosition = e.getPoint();
            updateModelviewMatrix();
        }

        @Override
        public void mouseMoved(MouseEvent e)
        {
            previousMousePosition = e.getPoint();
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e)
        {
            // Translate along the Z-axis
            translationZ += e.getWheelRotation() * 0.25f;
            previousMousePosition = e.getPoint();
            updateModelviewMatrix();
        }
    }

    /**
     * Inner class extending a KeyAdapter for the keyboard
     * interaction
     */
    class KeyboardControl extends KeyAdapter
    {
        @Override
        public void keyTyped(KeyEvent e)
        {
            char c = e.getKeyChar();
            if (c == 't')
            {
                useJOCL = !useJOCL;
                reInitVBOData = true;
                System.out.println("useJOCL is now "+useJOCL);
            }
        }
    }

    
    /**
     * Creates a new JOCLSimpleGL3 sample.
     */
    public JOCLSimpleLWJGL()
    {
        // Initialize the GL component 
        createCanvas();
        
        // Initialize the mouse and keyboard controls
        MouseControl mouseControl = new MouseControl();
        glComponent.addMouseMotionListener(mouseControl);
        glComponent.addMouseWheelListener(mouseControl);
        KeyboardControl keyboardControl = new KeyboardControl();
        glComponent.addKeyListener(keyboardControl);
        updateModelviewMatrix();        

        // Create the main frame 
        frame = new JFrame("JOCL / LWJGL interaction sample");
        frame.addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosing(WindowEvent e)
            {
                System.exit(0);
            }
        });
        frame.setLayout(new BorderLayout());
        glComponent.setPreferredSize(new Dimension(800, 800));
        frame.add(glComponent, BorderLayout.CENTER);
        frame.pack();
        frame.setVisible(true);
        glComponent.requestFocus();
        
    }
    
    /**
     * Create the AWTGLCanvas 
     */
    private void createCanvas()
    {
        try
        {
            glComponent = new AWTGLCanvas()
            {
                private static final long serialVersionUID = 1L;
                private boolean initialized = false;
                private Dimension previousSize = null;
                
                public void paintGL()
                {
                    if (!initialized)
                    {
                        init();
                        glComponent.setVSyncEnabled(false);
                        initialized = true;
                    }
                    if (previousSize == null || !previousSize.equals(getSize()))
                    {
                        previousSize = getSize();
                        setupView();
                    }
                    render();
                    try
                    {
                        swapBuffers();
                    }
                    catch (LWJGLException e)
                    {
                        throw new RuntimeException(
                            "Could not swap buffers", e);
                    }
                }
            };
        }
        catch (LWJGLException e)
        {
            throw new RuntimeException(
                "Could not create canvas", e);
        }
        glComponent.setFocusable(true);

        // Create the thread that triggers a repaint of the component
        Thread thread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    glComponent.repaint();
                    try
                    {
                        Thread.sleep(1);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Update the modelview matrix depending on the
     * current translation and rotation
     */
    private void updateModelviewMatrix()
    {
        float m0[] = translation(translationX, translationY, translationZ);
        float m1[] = rotationX(rotationX);
        float m2[] = rotationY(rotationY);
        modelviewMatrix = multiply(multiply(m1,m2), m0);
    }
    

    /**
     * Called to initialize the drawing and OpenCL
     */
    public void init()
    {
        // Perform the default GL initialization 
        glEnable(GL_DEPTH_TEST);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        // Initialize the shaders
        initShaders();
        
        // Set up the viewport and projection matrix
        setupView();

        // Initialize OpenCL, creating a context 
        initCL();

        // Initialize the OpenGL VBO and the OpenCL VBO memory object
        initVBOData();
    }


    /**
     * Initialize OpenCL. This will create the CL context, as well as the 
     * command queue and kernel.
     */
    private void initCL()
    {
        // The platform, device type and device number
        // that will be used
        final int platformIndex = 0;
        final long deviceType = CL_DEVICE_TYPE_GPU;
        final int deviceIndex = 0;

        // Enable exceptions and subsequently omit error checks in this sample
        CL.setExceptionsEnabled(true);

        // Obtain the number of platforms
        int numPlatformsArray[] = new int[1];
        clGetPlatformIDs(0, null, numPlatformsArray);
        int numPlatforms = numPlatformsArray[0];

        // Obtain a platform ID
        cl_platform_id platforms[] = new cl_platform_id[numPlatforms];
        clGetPlatformIDs(platforms.length, platforms, null);
        cl_platform_id platform = platforms[platformIndex];

        // Initialize the context properties
        cl_context_properties contextProperties = new cl_context_properties();
        contextProperties.addProperty(CL_CONTEXT_PLATFORM, platform);
        if (GL_INTEROP)
        {
            initContextProperties(contextProperties);
        }
        
        // Obtain the number of devices for the platform
        int numDevicesArray[] = new int[1];
        clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray);
        int numDevices = numDevicesArray[0];
        
        // Obtain a device ID 
        cl_device_id devices[] = new cl_device_id[numDevices];
        clGetDeviceIDs(platform, deviceType, numDevices, devices, null);
        cl_device_id device = devices[deviceIndex];

        // Create a context for the selected device
        context = clCreateContext(
            contextProperties, 1, new cl_device_id[]{device}, 
            null, null, null);
        
        // Create a command-queue for the selected device
        cl_queue_properties properties = new cl_queue_properties();
        commandQueue = clCreateCommandQueueWithProperties(
            context, device, properties, null);

        // Read the program source code and create the program
        String source = readFile("src/main/resources/kernels/simpleGL.cl");
        cl_program program = clCreateProgramWithSource(context, 1, 
            new String[]{ source }, null, null);
        clBuildProgram(program, 0, null, "-cl-mad-enable", null, null);

        // Create the kernel which computes the sine wave pattern
        kernel = clCreateKernel(program, "sine_wave", null);
        
        // Set the constant kernel arguments 
        clSetKernelArg(kernel, 1, Sizeof.cl_uint, 
            Pointer.to(new int[]{ meshWidth }));
        clSetKernelArg(kernel, 2, Sizeof.cl_uint, 
            Pointer.to(new int[]{ meshHeight }));
    }

    
    /**
     * Initializes the given context properties so that they may be
     * used to create an OpenCL context for OpenGL
     *  
     * @param contextProperties The context properties
     */
    private void initContextProperties(cl_context_properties contextProperties)
    {
        PointerBuffer properties = new PointerBuffer(10);
        try
        {
            glComponent.setCLSharingProperties(properties);
        }
        catch (LWJGLException e)
        {
            throw new RuntimeException("Could not obtain context properties", e);
        }
        int position = properties.position();
        for (int i=0; i<position; i+=2)
        {
            contextProperties.addProperty(properties.get(i), properties.get(i+1));
        }
    }

    
    
    /**
     * Helper function which reads the file with the given name and returns 
     * the contents of this file as a String. Will exit the application
     * if the file can not be read.
     * 
     * @param fileName The name of the file to read.
     * @return The contents of the file
     */
    private String readFile(String fileName)
    {
        try
        {
            BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(fileName)));
            StringBuffer sb = new StringBuffer();
            String line = null;
            while (true)
            {
                line = br.readLine();
                if (line == null)
                {
                    break;
                }
                sb.append(line).append("\n");
            }
            br.close();
            return sb.toString();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Initialize the shaders and the shader program
     */
    private void initShaders()
    {
        int vertexShaderID = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertexShaderID, toByteBuffer(vertexShaderSource));
        glCompileShader(vertexShaderID);

        int fragmentShaderID = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragmentShaderID, toByteBuffer(fragmentShaderSource));
        glCompileShader(fragmentShaderID);
        
        shaderProgramID = glCreateProgram();
        glAttachShader(shaderProgramID, vertexShaderID);
        glAttachShader(shaderProgramID, fragmentShaderID);
        glLinkProgram(shaderProgramID);
    }

    /**
     * Initialize the OpenGL VBO and the OpenCL VBO memory object
     */
    private void initVBOData()
    {
        initVBO();
        initVBOMem();
        reInitVBOData = false;
    }
    
    
    /**
     * Create the GL vertex buffer object (VBO) that stores the
     * vertex positions.
     */
    private void initVBO()
    {
        if (vertexBufferObject != 0)
        {
            tempIntBuffer.put(0, vertexBufferObject);
            glDeleteBuffers(tempIntBuffer);
            vertexBufferObject = 0;
        }
        if (vertexArrayObject != 0)
        {
            tempIntBuffer.put(0, vertexArrayObject);
            glDeleteBuffers(tempIntBuffer);
            vertexArrayObject = 0;
        }

        // Create the vertex array object
        tempIntBuffer.rewind();
        glGenVertexArrays(tempIntBuffer);
        vertexArrayObject = tempIntBuffer.get(0);
        
        // Create the vertex buffer object
        tempIntBuffer.rewind();
        glGenBuffers(tempIntBuffer);
        vertexBufferObject = tempIntBuffer.get(0);

        // Initialize the vertex buffer object
        glBindBuffer(GL_ARRAY_BUFFER, vertexBufferObject);
        int size = meshWidth * meshHeight * 4 * Sizeof.cl_float;
        glBufferData(GL_ARRAY_BUFFER, size, GL_DYNAMIC_DRAW);
        
        // Initialize the attribute location of the input
        // vertices for the shader program
        int location = glGetAttribLocation(shaderProgramID, "inVertex");
        glVertexAttribPointer(location, 4, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(location);
        
    }

    /**
     * Initialize the OpenCL VBO memory object which corresponds to
     * the OpenGL VBO that stores the vertex positions
     */
    private void initVBOMem()
    {
        if (vboMem != null)
        {
            clReleaseMemObject(vboMem);
            vboMem = null;
        }
        if (GL_INTEROP)
        {
            // Create an OpenCL buffer for the VBO
            glBindBuffer(GL_ARRAY_BUFFER, vertexBufferObject);
            vboMem = clCreateFromGLBuffer(context, CL_MEM_WRITE_ONLY, 
                vertexBufferObject, null);
        }
        else
        {
            // Create an empty OpenCL buffer
            int size = meshWidth * meshHeight * 4 * Sizeof.cl_float;
            vboMem = clCreateBuffer(context, CL_MEM_WRITE_ONLY, size, 
                null, null);
        }
        clSetKernelArg(kernel, 0, Sizeof.cl_mem, Pointer.to(vboMem));
    }
    
    /**
     * Called when the canvas is to be displayed.
     */
    public void render()
    {
        if (reInitVBOData)
        {
            initVBOData();
        }

        if (useJOCL)
        {
            // Run the JOCL kernel to generate new vertex positions.
            runJOCL();
        }
        else
        {
            // Run the Java method to generate new vertex positions.
            runJava();
        }
        
        animationState += 0.01f;

        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // Activate the shader program
        glUseProgram(shaderProgramID);
        
        // Set the current projection matrix
        int projectionMatrixLocation = 
            glGetUniformLocation(shaderProgramID, "projectionMatrix");
        projectionMatrixBuffer.rewind();
        projectionMatrixBuffer.put(projectionMatrix);
        projectionMatrixBuffer.rewind();
        glUniformMatrix4(
            projectionMatrixLocation, false, projectionMatrixBuffer);

        // Set the current modelview matrix
        int modelviewMatrixLocation = 
            glGetUniformLocation(shaderProgramID, "modelviewMatrix");
        modelviewMatrixBuffer.rewind();
        modelviewMatrixBuffer.put(modelviewMatrix);
        modelviewMatrixBuffer.rewind();
        glUniformMatrix4(
            modelviewMatrixLocation, false, modelviewMatrixBuffer);
        
        // Render the VBO
        glBindBuffer(GL_ARRAY_BUFFER, vertexBufferObject);
        glDrawArrays(GL_POINTS, 0, meshWidth * meshHeight);


        // Update FPS information in main frame title
        step++;
        long currentTime = System.nanoTime();
        if (prevTimeNS == -1)
        {
            prevTimeNS = currentTime;
        }
        long diff = currentTime - prevTimeNS;
        if (diff > 1e9)
        {
            double fps = (diff / 1e9) * step;
            String t = "JOCL / LWJGL interaction sample - ";
            t += useJOCL?"JOCL":"Java";
            t += " mode: "+String.format("%.2f", fps)+" FPS";
            frame.setTitle(t);
            prevTimeNS = currentTime;
            step = 0;
        }
    }

    /**
     * Run the JOCL computation to create new vertex positions
     * inside the vertexBufferObject.
     */
    private void runJOCL()
    {
        if (GL_INTEROP)
        {
            // Map OpenGL buffer object for writing from OpenCL
            glFinish();
            clEnqueueAcquireGLObjects(commandQueue, 1, 
                new cl_mem[]{ vboMem }, 0, null, null);
        }

        // Set work size and arguments, and execute the kernel
        long globalWorkSize[] = new long[2];
        globalWorkSize[0] = meshWidth;
        globalWorkSize[1] = meshHeight;
        clSetKernelArg(kernel, 3, Sizeof.cl_float, 
            Pointer.to(new float[]{ animationState }));
        clEnqueueNDRangeKernel(commandQueue, kernel, 2, null, 
            globalWorkSize, null, 0, null, null);

        if (GL_INTEROP)
        {
            // Unmap the buffer object
            clEnqueueReleaseGLObjects(commandQueue, 1, 
                new cl_mem[]{ vboMem }, 0, null, null);
            clFinish(commandQueue);
        }
        else
        {
            // Map the VBO to copy data from the CL buffer to the GL buffer,
            // copy the data from CL to GL, and unmap the buffer
            glBindBuffer(GL_ARRAY_BUFFER, vertexBufferObject);
            
            mappedBuffer = glMapBuffer(
                GL_ARRAY_BUFFER, GL_WRITE_ONLY, mappedBuffer);
            clEnqueueReadBuffer(commandQueue, vboMem, CL_TRUE, 0, 
                Sizeof.cl_float * 4 * meshHeight * meshWidth, 
                Pointer.to(mappedBuffer), 0, null, null);
            glUnmapBuffer(GL_ARRAY_BUFFER);
        }
    }

    /**
     * Run the Java computation to create new vertex positions
     * inside the vertexBufferObject.
     */
    private void runJava()
    {
        float currentAnimationState = animationState;
        
        glBindBuffer(GL_ARRAY_BUFFER, vertexBufferObject);
        
        mappedBuffer = glMapBuffer(
            GL_ARRAY_BUFFER, GL_WRITE_ONLY, mappedBuffer);
        FloatBuffer vertices = mappedBuffer.order(
            ByteOrder.nativeOrder()).asFloatBuffer();
        
        for (int x = 0; x < meshWidth; x++)
        {
            for (int y = 0; y < meshHeight; y++)
            {
                // Calculate u/v coordinates
                float u = x / (float) meshWidth;
                float v = y / (float) meshHeight;

                u = u * 2.0f - 1.0f;
                v = v * 2.0f - 1.0f;

                // Calculate simple sine wave pattern
                float freq = 4.0f;
                float w = 
                    (float) Math.sin(u * freq + currentAnimationState) * 
                    (float) Math.cos(v * freq + currentAnimationState) * 0.5f;

                // Write output vertex
                int index = 4 * (y * meshWidth + x);
                vertices.put(index + 0, u);
                vertices.put(index + 1, w);
                vertices.put(index + 2, v);
                vertices.put(index + 3, 1);
            }
        }
        glUnmapBuffer(GL_ARRAY_BUFFER);
    }

    /**
     * Set up a default view for the given GLAutoDrawable
     * 
     * @param drawable The GLAutoDrawable to set the view for
     */
    private void setupView()
    {
        glViewport(0, 0, glComponent.getWidth(), glComponent.getHeight());

        float aspect = (float) glComponent.getWidth() / glComponent.getHeight();
        projectionMatrix = perspective(50, aspect, 0.1f, 100.0f);
    }
    
    


    
    //=== Helper functions for buffers ========================================
    
    /**
     * Creates a direct buffer with the given number of elements and
     * native order
     * 
     * @param size The number of elements
     * @return The buffer
     */
    private static IntBuffer createIntBuffer(int size)
    {
        return ByteBuffer.allocateDirect(size * 4).
            order(ByteOrder.nativeOrder()).asIntBuffer();
    }
    
    /**
     * Creates a direct buffer with the given number of elements and
     * native order
     * 
     * @param size The number of elements
     * @return The buffer
     */
    private static FloatBuffer createFloatBuffer(int size)
    {
        return ByteBuffer.allocateDirect(size * 4).
            order(ByteOrder.nativeOrder()).asFloatBuffer();
    }
    
    /**
     * Creates a direct buffer with the given number of elements and
     * native order
     * 
     * @param size The number of elements
     * @return The buffer
     */
    private static ByteBuffer createByteBuffer(int size)
    {
        return ByteBuffer.allocateDirect(size).
            order(ByteOrder.nativeOrder());
    }
    
    /**
     * Converts the given String into a native ByteBuffer
     * 
     * @param s The string to convert
     * @return The buffer
     */
    private static ByteBuffer toByteBuffer(String s)
    {
        byte bytes[] = s.getBytes();
        ByteBuffer buffer = createByteBuffer(bytes.length+1);
        buffer.put(bytes);
        buffer.put((byte)0);
        buffer.rewind();
        return buffer;
    }
    
    
    //=== Helper functions for matrix operations ==============================

    /**
     * Helper method that creates a perspective matrix
     * @param fovy The fov in y-direction, in degrees
     * 
     * @param aspect The aspect ratio
     * @param zNear The near clipping plane
     * @param zFar The far clipping plane
     * @return A perspective matrix
     */
    private static float[] perspective(
        float fovy, float aspect, float zNear, float zFar)
    {
        float radians = (float)Math.toRadians(fovy / 2);
        float deltaZ = zFar - zNear;
        float sine = (float)Math.sin(radians);
        if ((deltaZ == 0) || (sine == 0) || (aspect == 0)) 
        {
            return identity();
        }
        float cotangent = (float)Math.cos(radians) / sine;
        float m[] = identity();
        m[0*4+0] = cotangent / aspect;
        m[1*4+1] = cotangent;
        m[2*4+2] = -(zFar + zNear) / deltaZ;
        m[2*4+3] = -1;
        m[3*4+2] = -2 * zNear * zFar / deltaZ;
        m[3*4+3] = 0;
        return m;
    }
    
    /**
     * Creates an identity matrix
     * 
     * @return An identity matrix 
     */
    private static float[] identity()
    {
        float m[] = new float[16];
        Arrays.fill(m, 0);
        m[0] = m[5] = m[10] = m[15] = 1.0f;
        return m;
    }
    
    /**
     * Multiplies the given matrices and returns the result
     * 
     * @param m0 The first matrix
     * @param m1 The second matrix
     * @return The product m0*m1
     */
    private static float[] multiply(float m0[], float m1[])
    {
        float m[] = new float[16];
        for (int x=0; x < 4; x++)
        {
            for(int y=0; y < 4; y++)
            {
                m[x*4 + y] = 
                    m0[x*4+0] * m1[y+ 0] +
                    m0[x*4+1] * m1[y+ 4] +
                    m0[x*4+2] * m1[y+ 8] +
                    m0[x*4+3] * m1[y+12];
            }
        }
        return m;
    }
    
    /**
     * Creates a translation matrix
     * 
     * @param x The x translation
     * @param y The y translation
     * @param z The z translation
     * @return A translation matrix
     */
    private static float[] translation(float x, float y, float z)
    {
        float m[] = identity();
        m[12] = x;
        m[13] = y;
        m[14] = z;
        return m;
    }

    /**
     * Creates a matrix describing a rotation around the x-axis
     * 
     * @param angleDeg The rotation angle, in degrees
     * @return The rotation matrix
     */
    private static float[] rotationX(float angleDeg)
    {
        float m[] = identity();
        float angleRad = (float)Math.toRadians(angleDeg);
        float ca = (float)Math.cos(angleRad);
        float sa = (float)Math.sin(angleRad);
        m[ 5] =  ca;
        m[ 6] =  sa;
        m[ 9] = -sa;
        m[10] =  ca;
        return m;
    }

    /**
     * Creates a matrix describing a rotation around the y-axis
     * 
     * @param angleDeg The rotation angle, in degrees
     * @return The rotation matrix
     */
    private static float[] rotationY(float angleDeg)
    {
        float m[] = identity();
        float angleRad = (float)Math.toRadians(angleDeg);
        float ca = (float)Math.cos(angleRad);
        float sa = (float)Math.sin(angleRad);
        m[ 0] =  ca;
        m[ 2] = -sa;
        m[ 8] =  sa;
        m[10] =  ca;
        return m;
    }
    
}
