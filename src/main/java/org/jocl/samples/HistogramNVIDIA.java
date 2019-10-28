/*
 * JOCL - Java bindings for OpenCL
 * 
 * Copyright 2009-2019 Marco Hutter - http://www.jocl.org/
 */
package org.jocl.samples;

import static org.jocl.CL.*;

import java.io.*;
import java.util.Random;

import org.jocl.*;

/**
 * This class is a port of the NVIDIA OpenCL SDK "Histogram" sample.
 * The structure of the code has intentionally been kept similar 
 * to the original sample.  
 */
public class HistogramNVIDIA
{
    public static final int HISTOGRAM256_BIN_COUNT = 256;
    
    //OpenCL histogram256 program
    static cl_program cpHistogram256;

    //OpenCL histogram256 kernels
    static cl_kernel ckHistogram256, ckMergeHistogram256;

    //histogram256() intermediate results buffer
    static int PARTIAL_HISTOGRAM256_COUNT = 240;
    static cl_mem d_PartialHistograms;

    //Default command queue for histogram256 kernels
    static cl_command_queue cqDefaultCommandQue;

    
    
    ////////////////////////////////////////////////////////////////////////////////
    //Test driver
    ////////////////////////////////////////////////////////////////////////////////
    public static void main(String args[])
    {
      cl_context       cxGPUContext; //OpenCL context
      cl_command_queue cqCommandQue; //OpenCL command que
      cl_mem    d_Data, d_Histogram; //OpenCL memory buffer objects

      long dataBytes[] = new long[1];
      int ciErrNum[] = new int[1];
      int PassFailFlag = 1;

      byte h_Data[];
      int h_HistogramCPU[], h_HistogramGPU[];
      
      int byteCount = 128 * 8192;

      // start logs
      System.out.println("Starting...\n"); 

      System.out.println("Initializing data...");
      h_Data         = new byte[byteCount];
      h_HistogramCPU = new int[HISTOGRAM256_BIN_COUNT];
      h_HistogramGPU = new int[HISTOGRAM256_BIN_COUNT];
      
      Random random = new Random(2009);
      for(int i = 0; i < byteCount; i++)
          h_Data[i] = (byte)(random.nextInt() & 0xFF);

      // This will allow us to subsequently omit the "shrCheckError" calls for this sample
      CL.setExceptionsEnabled(true);
      
      System.out.println("Initializing OpenCL...");

      // Obtain the platform IDs and initialize the context properties
      cl_platform_id platforms[] = new cl_platform_id[1];
      clGetPlatformIDs(platforms.length, platforms, null);
      cl_context_properties contextProperties = new cl_context_properties();
      contextProperties.addProperty(CL_CONTEXT_PLATFORM, platforms[0]);
      cxGPUContext = clCreateContextFromType(contextProperties, CL_DEVICE_TYPE_GPU, null, null, ciErrNum);

      // get the list of GPU devices associated with context
      clGetContextInfo(cxGPUContext, CL_CONTEXT_DEVICES, 0, null, dataBytes);
      cl_device_id cdDevices[] = new cl_device_id[(int)dataBytes[0] / Sizeof.cl_device_id];
      
      clGetContextInfo(cxGPUContext, CL_CONTEXT_DEVICES, dataBytes[0], Pointer.to(cdDevices), null);

      //Create a command-queue
      cl_queue_properties properties = new cl_queue_properties();
      cqCommandQue = clCreateCommandQueueWithProperties(
          cxGPUContext, cdDevices[0], properties, ciErrNum);
      
      System.out.println("Allocating OpenCL memory...\n");
      d_Data = clCreateBuffer(cxGPUContext, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, byteCount * Sizeof.cl_char, Pointer.to(h_Data), ciErrNum);
      d_Histogram = clCreateBuffer(cxGPUContext, CL_MEM_READ_WRITE, HISTOGRAM256_BIN_COUNT * Sizeof.cl_int, null, ciErrNum);

      System.out.println("Initializing 256-bin OpenCL histogram...");
      initHistogram256(cxGPUContext, cqCommandQue);

      System.out.printf("Running 256-bin OpenCL histogram for %d bytes...\n", byteCount);
      histogram256(null, d_Histogram, d_Data, byteCount);

      System.out.println("Validating OpenCL results...");
      System.out.println("...reading back OpenCL results");
      clEnqueueReadBuffer(cqCommandQue, d_Histogram, CL_TRUE, 0, HISTOGRAM256_BIN_COUNT * Sizeof.cl_int, Pointer.to(h_HistogramGPU), 0, null, null);

      System.out.println("...histogram256CPU()");

      histogram256CPU(h_HistogramCPU, h_Data, byteCount);

      for(int i = 0; i < HISTOGRAM256_BIN_COUNT; i++)
      {
          if(h_HistogramGPU[i] != h_HistogramCPU[i])
          {
              PassFailFlag = 0;
          }
      }
      System.out.println(PassFailFlag != 0 ? "256-bin histograms match\n" : "***256-bin histograms do not match!!!***\n" );

      System.out.println("Shutting down 256-bin OpenCL histogram...\n\n"); 

      //Release kernels and program
      closeHistogram256();

      // pass or fail
      System.out.printf("TEST %s\n", PassFailFlag != 0 ? "PASSED" : "FAILED !!!");

      System.out.println("Shutting down...");

      //Release other OpenCL Objects
      ciErrNum[0]  = clReleaseMemObject(d_Histogram);
      ciErrNum[0] |= clReleaseMemObject(d_Data);
      ciErrNum[0] |= clReleaseCommandQueue(cqCommandQue);
      ciErrNum[0] |= clReleaseContext(cxGPUContext);
    }
    
    
    static void histogram256CPU(int h_Histogram[], byte h_Data[], int byteCount)
    {
        for(int i = 0; i < HISTOGRAM256_BIN_COUNT; i++)
            h_Histogram[i] = 0;

        for(int i = 0; i < byteCount; i++){
            int data = h_Data[i];
            if (data < 0)
            {
                data+=256;
            }
            h_Histogram[data]++;
        }
    }
    

    ////////////////////////////////////////////////////////////////////////////////
    // OpenCL launchers for histogram256 / mergeHistogram256 kernels
    ////////////////////////////////////////////////////////////////////////////////

    static void initHistogram256(cl_context cxGPUContext, cl_command_queue cqParamCommandQue)
    {
        int ciErrNum[] = new int[1];

        System.out.println("...loading Histogram256.cl");
        String cHistogram256 = readFile("src/main/resources/kernels/Histogram256.cl");

        System.out.println("...creating histogram256 program");
        cpHistogram256 = clCreateProgramWithSource(cxGPUContext, 1, new String[]{cHistogram256}, new long[]{cHistogram256.length()}, ciErrNum);
        
        System.out.println("...building histogram256 program");
        ciErrNum[0] = clBuildProgram(cpHistogram256, 0, null, null, null, null);

        System.out.println("...creating histogram256 kernels");
        ckHistogram256 = clCreateKernel(cpHistogram256, "histogram256", ciErrNum);
        ckMergeHistogram256 = clCreateKernel(cpHistogram256, "mergeHistogram256", ciErrNum);

        System.out.println("...allocating internal histogram256 buffer");
        d_PartialHistograms = clCreateBuffer(cxGPUContext, CL_MEM_READ_WRITE, PARTIAL_HISTOGRAM256_COUNT * HISTOGRAM256_BIN_COUNT * Sizeof.cl_uint, null, ciErrNum);

        //Save default command queue
        cqDefaultCommandQue = cqParamCommandQue;
    }

    static void closeHistogram256()
    {
        clReleaseMemObject(d_PartialHistograms);
        clReleaseKernel(ckMergeHistogram256);
        clReleaseKernel(ckHistogram256);
        clReleaseProgram(cpHistogram256);
    }

    static void histogram256(cl_command_queue cqCommandQue, cl_mem d_Histogram, cl_mem d_Data, int byteCount)
    {
        long localWorkSize[] = new long[1];
        long globalWorkSize[] = new long[1];

        if(cqCommandQue == null)
            cqCommandQue = cqDefaultCommandQue;

        int WARP_SIZE = 32;
        int WARP_COUNT = 6;

        int dataCount = byteCount / 4;
        clSetKernelArg(ckHistogram256, 0, Sizeof.cl_mem,  Pointer.to(d_PartialHistograms));
        clSetKernelArg(ckHistogram256, 1, Sizeof.cl_mem,  Pointer.to(d_Data));
        clSetKernelArg(ckHistogram256, 2, Sizeof.cl_uint, Pointer.to(new int[]{dataCount}));

        localWorkSize[0]  = WARP_SIZE * WARP_COUNT;
        globalWorkSize[0] = PARTIAL_HISTOGRAM256_COUNT * localWorkSize[0];

        clEnqueueNDRangeKernel(cqCommandQue, ckHistogram256, 1, null, globalWorkSize, localWorkSize, 0, null, null);

        int MERGE_WORKGROUP_SIZE = 256;
        clSetKernelArg(ckMergeHistogram256, 0, Sizeof.cl_mem,  Pointer.to(d_Histogram));
        clSetKernelArg(ckMergeHistogram256, 1, Sizeof.cl_mem,  Pointer.to(d_PartialHistograms));
        clSetKernelArg(ckMergeHistogram256, 2, Sizeof.cl_uint, Pointer.to(new int[]{PARTIAL_HISTOGRAM256_COUNT}));

        localWorkSize[0]  = MERGE_WORKGROUP_SIZE;
        globalWorkSize[0] = HISTOGRAM256_BIN_COUNT * localWorkSize[0];

        clEnqueueNDRangeKernel(cqCommandQue, ckMergeHistogram256, 1, null, globalWorkSize, localWorkSize, 0, null, null);
    }

    
    private static String readFile(String fileName)
    {
        BufferedReader br = null;
        try
        {
            br = new BufferedReader(new FileReader(fileName));
            StringBuilder sb = new StringBuilder();
            String line = null;
            while (true)
            {
                line = br.readLine();
                if (line == null)
                {
                    break;
                }
                sb.append(line+"\n");
            }
            return sb.toString();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            return "";
        }
        finally
        {
            if (br != null)
            {
                try
                {
                    br.close();
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }
    }
    
}
