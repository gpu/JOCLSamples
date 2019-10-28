/*
 * JOCL - Java bindings for OpenCL
 * 
 * Copyright 2009-2019 Marco Hutter - http://www.jocl.org/
 */
package org.jocl.samples;

import static org.jocl.CL.*;

import java.awt.*;
import java.awt.image.*;
import java.io.*;

import javax.imageio.ImageIO;
import javax.swing.*;

import org.jocl.*;

/**
 * A simple example demonstrating image handling between JOCL
 * and Swing. It shows an animation of a rotating image,
 * which is rotated using an OpenCL kernel involving some
 * basic image operations.
 */
public class JOCLSimpleImage
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
                new JOCLSimpleImage();
            }
        });
    }

    /**
     * The source code of the kernel to execute. It will rotate the
     * input image by the given angle and write the result into the
     * output image.
     */
    private static String programSource =
        ""+ "\n" +
        "const sampler_t samplerIn = "+ "\n" +
        "    CLK_NORMALIZED_COORDS_FALSE | "+ "\n" +
        "    CLK_ADDRESS_CLAMP |"+ "\n" +
        "    CLK_FILTER_NEAREST;"+ "\n" +
        ""+ "\n" +
        "const sampler_t samplerOut = "+ "\n" +
        "    CLK_NORMALIZED_COORDS_FALSE |"+ "\n" +
        "    CLK_ADDRESS_CLAMP |"+ "\n" +
        "    CLK_FILTER_NEAREST;"+ "\n" +
        ""+ "\n" +
        "__kernel void rotateImage("+ "\n" +
        "    __read_only  image2d_t sourceImage, "+ "\n" +
        "    __write_only image2d_t targetImage, "+ "\n" +
        "    float angle)"+ "\n" +
        "{"+ "\n" +
        "    int gidX = get_global_id(0);"+ "\n" +
        "    int gidY = get_global_id(1);"+ "\n" +
        "    int w = get_image_width(sourceImage);"+ "\n" +
        "    int h = get_image_height(sourceImage);"+ "\n" +
        "    int cx = w/2;"+ "\n" +
        "    int cy = h/2;"+ "\n" +
        "    int dx = gidX-cx;"+ "\n" +
        "    int dy = gidY-cy;"+ "\n" +
        "    float ca = cos(angle);"+ "\n" +
        "    float sa = sin(angle);"+ "\n" +
        "    int inX = (int)(cx+ca*dx-sa*dy);"+ "\n" +
        "    int inY = (int)(cy+sa*dx+ca*dy);"+ "\n" +
        "    int2 posIn = {inX, inY};"+ "\n" +
        "    int2 posOut = {gidX, gidY};"+ "\n" +
        "    uint4 pixel = read_imageui(sourceImage, samplerIn, posIn);"+ "\n" +
        "    write_imageui(targetImage, posOut, pixel);"+ "\n" +
        "}";


    /**
     * Creates a BufferedImage of with type TYPE_INT_RGB from the
     * file with the given name.
     *
     * @param fileName The file name
     * @return The image, or null if the file may not be read
     */
    private static BufferedImage createBufferedImage(String fileName)
    {
        BufferedImage image = null;
        try
        {
            image = ImageIO.read(new File(fileName));
        }
        catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }

        int sizeX = image.getWidth();
        int sizeY = image.getHeight();

        BufferedImage result = new BufferedImage(
            sizeX, sizeY, BufferedImage.TYPE_INT_RGB);
        Graphics g = result.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return result;
    }

    /**
     * The input image
     */
    private BufferedImage inputImage;

    /**
     * The output image
     */
    private BufferedImage outputImage;

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
     * The memory object for the input image
     */
    private cl_mem inputImageMem;

    /**
     * The memory object for the output image
     */
    private cl_mem outputImageMem;

    /**
     * The width of the image
     */
    private int imageSizeX;

    /**
     * The height of the image
     */
    private int imageSizeY;

    /**
     * Creates the JOCLSimpleImage sample
     */
    public JOCLSimpleImage()
    {
        // Read the input image file and create the output images
        String fileName = "src/main/resources/data/lena512color.png";

        inputImage = createBufferedImage(fileName);
        imageSizeX = inputImage.getWidth();
        imageSizeY = inputImage.getHeight();

        outputImage = new BufferedImage(
            imageSizeX, imageSizeY, BufferedImage.TYPE_INT_RGB);

        // Create the panel showing the input and output images
        JPanel mainPanel = new JPanel(new GridLayout(1,0));
        JLabel inputLabel = new JLabel(new ImageIcon(inputImage));
        mainPanel.add(inputLabel, BorderLayout.CENTER);
        JLabel outputLabel = new JLabel(new ImageIcon(outputImage));
        mainPanel.add(outputLabel, BorderLayout.CENTER);

        // Create the main frame
        JFrame frame = new JFrame("JOCL Simple Image Sample");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(mainPanel, BorderLayout.CENTER);
        frame.pack();
        frame.setVisible(true);

        initCL();
        initImageMem();
        startAnimation(outputLabel);
    }


    /**
     * Starts the thread which will advance the animation state
     * and call call the animation method.
     *
     * @param outputComponent The component to repaint after each step
     */
    private void startAnimation(final Component outputComponent)
    {
        System.out.println("Starting animation...");
        Thread thread = new Thread(new Runnable()
        {
            float angle = 0.0f;
            public void run()
            {
                while (true)
                {
                    rotateImage(angle);
                    angle += 0.1f;
                    outputComponent.repaint();

                    try
                    {
                        Thread.sleep(20);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }


    /**
     * Initialize the OpenCL context, command queue and kernel
     */
    void initCL()
    {
        final int platformIndex = 0;
        final long deviceType = CL_DEVICE_TYPE_ALL;
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
        
        // Check if images are supported
        int imageSupport[] = new int[1];
        clGetDeviceInfo (device, CL.CL_DEVICE_IMAGE_SUPPORT,
            Sizeof.cl_int, Pointer.to(imageSupport), null);
        System.out.println("Images supported: "+(imageSupport[0]==1));
        if (imageSupport[0]==0)
        {
            System.out.println("Images are not supported");
            System.exit(1);
            return;
        }

        // Create a command-queue for the selected device
        cl_queue_properties properties = new cl_queue_properties();
        properties.addProperty(CL_QUEUE_PROFILING_ENABLE, 1);
        properties.addProperty(CL_QUEUE_OUT_OF_ORDER_EXEC_MODE_ENABLE, 1);
        commandQueue = clCreateCommandQueueWithProperties(
            context, device, properties, null);
        
        // Create the program
        System.out.println("Creating program...");
        cl_program program = clCreateProgramWithSource(context,
            1, new String[]{ programSource }, null, null);

        // Build the program
        System.out.println("Building program...");
        clBuildProgram(program, 0, null, null, null, null);

        // Create the kernel
        System.out.println("Creating kernel...");
        kernel = clCreateKernel(program, "rotateImage", null);

    }

    /**
     * Initialize the memory objects for the input and output images
     */
    private void initImageMem()
    {
        // Create the memory object for the input- and output image
        DataBufferInt dataBufferSrc =
            (DataBufferInt)inputImage.getRaster().getDataBuffer();
        int dataSrc[] = dataBufferSrc.getData();

        cl_image_format imageFormat = new cl_image_format();
        imageFormat.image_channel_order = CL_RGBA;
        imageFormat.image_channel_data_type = CL_UNSIGNED_INT8;

        inputImageMem = clCreateImage2D(
            context, CL_MEM_READ_ONLY | CL_MEM_USE_HOST_PTR,
            new cl_image_format[]{imageFormat}, imageSizeX, imageSizeY,
            imageSizeX * Sizeof.cl_uint, Pointer.to(dataSrc), null);

        outputImageMem = clCreateImage2D(
            context, CL_MEM_WRITE_ONLY,
            new cl_image_format[]{imageFormat}, imageSizeX, imageSizeY,
            0, null, null);
    }


    /**
     * Rotate the input image by the given angle, and write it into
     * the output image
     *
     * @param angle The rotation angle
     */
    void rotateImage(float angle)
    {
        // Set up the work size and arguments, and execute the kernel
        long globalWorkSize[] = new long[2];
        globalWorkSize[0] = imageSizeX;
        globalWorkSize[1] = imageSizeY;
        clSetKernelArg(kernel, 0, Sizeof.cl_mem, Pointer.to(inputImageMem));
        clSetKernelArg(kernel, 1, Sizeof.cl_mem, Pointer.to(outputImageMem));
        clSetKernelArg(kernel, 2, Sizeof.cl_float,
            Pointer.to(new float[]{angle}));
        clEnqueueNDRangeKernel(commandQueue, kernel, 2, null,
            globalWorkSize, null, 0, null, null);

        // Read the pixel data into the output image
        DataBufferInt dataBufferDst =
            (DataBufferInt)outputImage.getRaster().getDataBuffer();
        int dataDst[] = dataBufferDst.getData();
        clEnqueueReadImage(
            commandQueue, outputImageMem, true, new long[3],
            new long[]{imageSizeX, imageSizeY, 1},
            imageSizeX * Sizeof.cl_uint, 0,
            Pointer.to(dataDst), 0, null, null);
    }
}

