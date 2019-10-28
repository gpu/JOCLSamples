
// The reduction kernel that is described as "Two-stage reduction" at
// http://developer.amd.com/resources/documentation-articles/
//   articles-whitepapers/opencl-optimization-case-study-simple-reductions/
// adjusted to perform an ADD-reduction instead of a MIN-reduction
 
__kernel void reduce(
    __global float* buffer,
    __local float* scratch,
    __const int length,
    __global float* result) 
{
    int globalIndex = get_global_id(0);
    float accumulator = 0;

    // Loop sequentially over chunks of input vector
    while (globalIndex < length) 
    {
        float element = buffer[globalIndex];
        accumulator += element;
        globalIndex += get_global_size(0);
    }

    // Perform parallel reduction
    int lid = get_local_id(0);
    scratch[lid] = accumulator;
    barrier(CLK_LOCAL_MEM_FENCE);
    for(int offset = get_local_size(0) / 2; offset > 0; offset = offset / 2) 
    {
        if (lid < offset) 
        {
            float other = scratch[lid + offset];
            float mine = scratch[lid];
            scratch[lid] = mine + other;
        }
        barrier(CLK_LOCAL_MEM_FENCE);
    }
    if (lid == 0) 
    {
        result[get_group_id(0)] = scratch[0];
    }
}