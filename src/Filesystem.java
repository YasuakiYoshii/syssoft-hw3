public interface Filesystem {
    int ROOTINO = 1; // root i-number
    int BSIZE = 512; // block size

    int NDIRECT = 12;
    int SIZEOFINT = 4;
    int NINDIRECT = (BSIZE / SIZEOFINT);
    int MAXFILE = (NDIRECT + NINDIRECT);
}
