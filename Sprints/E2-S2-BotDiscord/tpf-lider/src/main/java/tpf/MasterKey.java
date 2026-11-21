package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;

final class MasterKey {
    private MasterKey() { }

    static byte[] loadOrCreate(Path path) throws IOException {
        if (!Files.exists(path)) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            if (Files.getFileAttributeView(path.getParent(), PosixFileAttributeView.class) != null) {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                UserPrincipal owner = path.getFileSystem().getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name"));
                AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
                Files.createFile(path, new FileAttribute<List<AclEntry>>() {
                    public String name() { return "acl:acl"; }
                    public List<AclEntry> value() { return List.of(entry); }
                });
            }
            try (FileChannel file = FileChannel.open(path, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(key);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
        }
        if (Files.isSymbolicLink(path) || Files.size(path) != 32) throw new IOException("master.key inválida: são necessários 32 bytes.");
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"));
        } else {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view == null) throw new IOException("O sistema de ficheiros não permite proteger a master.key.");
            AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                    .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES,
                            AclEntryPermission.READ_NAMED_ATTRS, AclEntryPermission.READ_ACL,
                            AclEntryPermission.WRITE_ACL, AclEntryPermission.DELETE, AclEntryPermission.SYNCHRONIZE).build();
            view.setAcl(List.of(entry));
        }
        return Files.readAllBytes(path);
    }
}
