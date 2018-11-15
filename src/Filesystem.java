public interface Filesystem {
    /* fs.h */
    int ROOTINO = 1; // root i-number
    int BSIZE = 512; // block size

    int NDIRECT = 12;
    int SIZEOFINT = 4;
    int NINDIRECT = (BSIZE / SIZEOFINT);
    int MAXFILE = (NDIRECT + NINDIRECT);

    int BPB = (BSIZE*8);



    int DIRSIZ = 14;

    class dinode {
        short type;           // File type
        short major;          // Major device number (T_DEV only)
        short minor;          // Minor device number (T_DEV only)
        short nlink;          // Number of links to inode in file system
        int size;            // Size of file (bytes)
        int[] addrs = new int[NDIRECT+1]; // Data block addresses
    }

    class dirent {
        short inum;
        char[] name = new char[DIRSIZ];
    }
}
