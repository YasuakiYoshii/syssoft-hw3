public class Superblock {
    int size;         // Size of file system image (blocks)
    int nblocks;      // Number of data blocks
    int ninodes;      // Number of inodes.
    int nlog;         // Number of log blocks
    int logstart;     // Block number of first log block
    int inodestart;   // Block number of first inode block
    int bmapstart; // Block number of first free map block
}
