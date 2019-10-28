/*
 * JOCL - Java bindings for OpenCL
 * 
 * Copyright 2009-2019 Marco Hutter - http://www.jocl.org/
 */
package org.jocl.samples.blast;
 
import static org.jocl.CL.*;
import static org.jocl.blast.CLBlast.*;
import static org.jocl.blast.CLBlastLayout.CLBlastLayoutRowMajor;
import static org.jocl.blast.CLBlastTranspose.CLBlastTransposeNo;
 
import java.nio.DoubleBuffer;
import java.util.Locale;
 
import org.jocl.*;
import org.jocl.blast.CLBlast;
 
/**
 * A basic sample showing how to use JOCLBlast to perform a DGEMM
 */
public class JOCLBlastDgemmSample
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
       
        // Create the host input data:
        // Matrix A with size MxK
        // Matrix B with size   KxN
        // Matrix C with size M x N
        int M = 4;
        int N = 3;
        int K = 5;
        double A[] =  
        {
            11, 12, 13, 14, 15,
            21, 22, 23, 24, 25,
            31, 32, 33, 34, 35,
            41, 42, 43, 44, 45,
        };
        double B[] =
        {
            11, 12, 13,
            21, 22, 23,
            31, 32, 33,
            41, 42, 43,
            51, 52, 53,
        };
        double C[] =
        {
            11, 12, 13,
            21, 22, 23,
            31, 32, 33,
            41, 42, 43,
        };
       
        // Create the device input buffers
        cl_mem memA = clCreateBuffer(context, CL_MEM_READ_ONLY,
            M * K * Sizeof.cl_double, null, null);
        cl_mem memB = clCreateBuffer(context, CL_MEM_READ_ONLY,
            K * N * Sizeof.cl_double, null, null);
        cl_mem memC = clCreateBuffer(context, CL_MEM_READ_WRITE,
            M * N * Sizeof.cl_double, null, null);
 
        // Copy the host data to the device
        clEnqueueWriteBuffer(commandQueue, memA, CL_TRUE, 0,
            M * K * Sizeof.cl_double, Pointer.to(A), 0, null, null);
        clEnqueueWriteBuffer(commandQueue, memB, CL_TRUE, 0,
            K * N * Sizeof.cl_double, Pointer.to(B), 0, null, null);
        clEnqueueWriteBuffer(commandQueue, memC, CL_TRUE, 0,
            M * N * Sizeof.cl_double, Pointer.to(C), 0, null, null);
 
        // Execute GEMM:
        // C = alpha * A * B + beta * C
        double alpha = 10;
        double beta = 20;
        cl_event event = new cl_event();
        CLBlastDgemm(
            CLBlastLayoutRowMajor, CLBlastTransposeNo, CLBlastTransposeNo, 
            M, N, K, alpha,
            memA, 0, K,
            memB, 0, N, beta,
            memC, 0, N,
            commandQueue, event);
       
        // Wait for the computation to be finished
        clWaitForEvents( 1, new cl_event[] { event });
 
        // Copy the result data back to the host
        double result[] = new double[M*N];
        clEnqueueReadBuffer(commandQueue, memC, CL_TRUE, 0,
            M * N * Sizeof.cl_double, Pointer.to(result), 0, null, null);
 
        // Print the inputs and the result
        System.out.println("A:");
        print2D(DoubleBuffer.wrap(A), K);
 
        System.out.println("B:");
        print2D(DoubleBuffer.wrap(B), N);
 
        System.out.println("C:");
        print2D(DoubleBuffer.wrap(C), N);
       
        System.out.println(
            "Result of C = " + alpha + " * A * B + " + beta + " * C:");
        print2D(DoubleBuffer.wrap(result), N);
 
        // Clean up
        clReleaseMemObject(memA);
        clReleaseMemObject(memB);
        clReleaseMemObject(memC);
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
     * Print the given buffer as a matrix with the given number of columns
     *
     * @param data The buffer
     * @param columns The number of columns
     */
    private static void print2D(DoubleBuffer data, int columns)
    {
        StringBuffer sb = new StringBuffer();
        for (int i=0; i<data.capacity(); i++)
        {
            sb.append(String.format(Locale.ENGLISH, "%5.1f ", data.get(i)));
            if (((i+1)%columns)==0)
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
        clGetDeviceInfo(device, paramName, buffer.length, Pointer.to(buffer), null);
 
        // Create a string from the buffer (excluding the trailing \0 byte)
        return new String(buffer, 0, buffer.length-1);
    }
 
}