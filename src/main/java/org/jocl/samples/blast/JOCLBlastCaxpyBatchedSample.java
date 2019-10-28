/*
 * JOCL - Java bindings for OpenCL
 * 
 * Copyright 2009-2019 Marco Hutter - http://www.jocl.org/
 */
package org.jocl.samples.blast;
 
import static org.jocl.CL.*;
import static org.jocl.blast.CLBlast.CLBlastCaxpyBatched;

import java.nio.FloatBuffer;
import java.util.Locale;

import org.jocl.*;
import org.jocl.blast.CLBlast;

/**
 * An example for using the batched CAXPY function from CLBlast to compute
 * Y = a * X + Y
 * for several single-precision complex number vectors
 */
public class JOCLBlastCaxpyBatchedSample
{
    private static cl_context context;
    private static cl_command_queue commandQueue;
 
    /**
     * The entry point of this sample
     *
     * @param args Not used
     */
    public static void main(String args[])
    {
        CL.setExceptionsEnabled(true);
        CLBlast.setExceptionsEnabled(true);
 
        defaultInitialization();
       

        // Create the host input data. Each entry of these vectors consists 
        // of TWO values, which are the real- and imaginary part of the 
        // complex number
        int numVectors = 3;
        int vectorSize = 5;
        
        // 3 vectors, each with 5 dimensions (*2, for real- and imaginary part)
        float X[] =  
        {
            1,1, 1,2, 1,3, 1,4, 1,5,
            2,1, 2,2, 2,3, 2,4, 2,5,
            3,1, 3,2, 3,3, 3,4, 3,5,
        };
        // 3 vectors, each with 5 dimensions (*2, for real- and imaginary part)
        float Y[] =
        {
            4,1, 4,2, 4,3, 4,4, 4,5,
            5,1, 5,2, 5,3, 5,4, 5,5,
            6,1, 6,2, 6,3, 6,4, 6,5,
        };
       
        // Create the device input buffers
        cl_mem memX = clCreateBuffer(context, CL_MEM_READ_ONLY,
            vectorSize * numVectors * Sizeof.cl_float2, null, null);
        cl_mem memY = clCreateBuffer(context, CL_MEM_READ_ONLY,
            vectorSize * numVectors * Sizeof.cl_float2, null, null);
 
        // Copy the host data to the device
        clEnqueueWriteBuffer(commandQueue, memX, CL_TRUE, 0,
            vectorSize * numVectors * Sizeof.cl_float2, 
            Pointer.to(X), 0, null, null);
        clEnqueueWriteBuffer(commandQueue, memY, CL_TRUE, 0,
            vectorSize * numVectors * Sizeof.cl_float2, 
            Pointer.to(Y), 0, null, null);
 
        // 3 factors to be multiplied with X (*2, for real- and imaginary part)
        float alphas[] = { 1,2, 2,3, 3,4 };
        
        // Execute batched CAXPY: Y = alpha * X + Y
        cl_event event = new cl_event();
        CLBlastCaxpyBatched(vectorSize, alphas, 
            memX, new long[] { 0, 5, 10 }, 1, 
            memY, new long[] { 0, 5, 10 }, 1,  
            numVectors, commandQueue, event);
       
        // Wait for the computation to be finished
        clWaitForEvents( 1, new cl_event[] { event });
 
        // Copy the result data back to the host
        float resultY[] = new float[vectorSize * numVectors * 2];
        clEnqueueReadBuffer(commandQueue, memY, CL_TRUE, 0,
            vectorSize * numVectors * Sizeof.cl_float2, 
            Pointer.to(resultY), 0, null, null);
 
        // Print the inputs and the result
        System.out.println("a:");
        printComplex2D(FloatBuffer.wrap(alphas), 1);
 
        System.out.println("X:");
        printComplex2D(FloatBuffer.wrap(X), vectorSize);

        System.out.println("Y:");
        printComplex2D(FloatBuffer.wrap(Y), vectorSize);
 
        System.out.println("Result:");
        printComplex2D(FloatBuffer.wrap(resultY), vectorSize);
       
        // Clean up
        clReleaseMemObject(memX);
        clReleaseMemObject(memY);
        clReleaseCommandQueue(commandQueue);
        clReleaseContext(context);        
    }
   
    /**
     * Default OpenCL initialization of the context and command queue
     */
    private static void defaultInitialization()
    {
        // The platform, device type and device number
        // that will be used
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
       
        String deviceName = getString(devices[0], CL_DEVICE_NAME);
        System.out.printf("CL_DEVICE_NAME: %s\n", deviceName);
       
        // Create a command-queue for the selected device
        cl_queue_properties properties = new cl_queue_properties();
        commandQueue = clCreateCommandQueueWithProperties(
            context, device, properties, null);
    }
   
    /**
     * Print the given buffer as a matrix with the given number of columns.
     * This assumes that the the elements of these buffers are complex 
     * numbers, consisting of a real- and an imaginary part.
     *
     * @param data The buffer
     * @param columns The number of columns
     */
    private static void printComplex2D(FloatBuffer data, int columns)
    {
        StringBuffer sb = new StringBuffer();
        for (int i=0; i<data.capacity() / 2; i++)
        {
            sb.append(String.format(Locale.ENGLISH, "(%5.1f, %5.1fi) ",
                data.get(i * 2 + 0), data.get(i * 2 + 1)));
            if (((i + 1) % columns) == 0)
            {
                sb.append("\n");
            }
        }
        System.out.print(sb.toString());
    }
   
    private static String getString(cl_device_id device, int paramName)
    {
        // Obtain the length of the string that will be queried
        long size[] = new long[1];
        clGetDeviceInfo(device, paramName, 0, null, size);
 
        // Create a buffer of the appropriate size and fill it with the info
        byte buffer[] = new byte[(int)size[0]];
        clGetDeviceInfo(device, paramName, buffer.length, 
            Pointer.to(buffer), null);
 
        // Create a string from the buffer (excluding the trailing \0 byte)
        return new String(buffer, 0, buffer.length-1);
    }
 
}