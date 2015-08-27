package de.johni0702.mc.replay.converter;

import de.johni0702.mc.protocolgen.NetUtils;
import de.johni0702.mc.protocolgen.Packet;
import de.johni0702.mc.protocolgen.play.client.*;
import de.johni0702.mc.protocolgen.play.server.*;
import de.johni0702.mc.protocolgen.play.server.PacketCustomPayload;
import de.johni0702.mc.protocolgen.play.server.PacketKeepAlive;
import de.johni0702.mc.protocolgen.play.server.PacketPosition;
import de.johni0702.mc.protocolgen.types.EntityMetadata;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;

import java.io.*;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Main {
    private static final int ANIMATION_SWING_ARM = 0;

    public static void main(String[] args) throws IOException, InstantiationException, IllegalAccessException {
        byte[] buf = new byte[8192];
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(args[1]));
             ZipInputStream in = new ZipInputStream(new FileInputStream(args[0]))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                out.putNextEntry(entry);
                if ("recording.tmcpr".equals(entry.getName())) {
                    transform(in, new DataOutputStream(out));
                } else {
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                    }
                }
                out.closeEntry();
            }
        }
    }

    private static final ProtocolServerPlay SERVER_PROTOCOL = new ProtocolServerPlay();
    private static final ProtocolClientPlay CLIENT_PROTOCOL = new ProtocolClientPlay();
    private static final Map<Class<? extends Packet>, Integer> REVERSE_PACKET_MAP =
            CLIENT_PROTOCOL.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

    private static void transform(InputStream is, DataOutputStream os) throws IOException, IllegalAccessException, InstantiationException {
//        readVar(is); // Time and direction of UUID
//        if (readVar(is) != 16) throw new IllegalArgumentException("UUID prefix must be 16 bytes long.");
//        UUID clientUuid = new UUID(readLong(is), readLong(is));
        UUID clientUuid = new UUID(-2316193157022402550L, -5255471254594452537L);
        boolean spawned = false;
        int clientEntityId = 0;
        int clientX = 0, clientY = 0, clientZ = 0;
        byte clientYaw = 0, clientPitch = 0;
        short[] hotbar = new short[9];
        int selectedHotbarSlot = 0;

        while (true) {
            long timeAndDirection = readVar(is);
            if (timeAndDirection == -1) {
                return;
            }
            long time = timeAndDirection >> 1;
            boolean fromServer = (timeAndDirection & 0x1) == 0;
            int length = (int) readVar(is);
            ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(length, length);
            while (length > 0) {
                length -= buf.writeBytes(is, length);
            }

            int packetId = (int) readVar(new ByteBufInputStream(buf));
            Class<? extends Packet> packetClass = (fromServer ? CLIENT_PROTOCOL : SERVER_PROTOCOL).get(packetId);
            if (packetClass == null) {
                System.out.println("Unknown packet id " + packetId + ": " + ByteBufUtil.prettyHexDump(buf));
                continue;
            }
            Packet packet = packetClass.newInstance();
            packet.read(buf);
            buf.release();

            if (fromServer) {
                writePacket(os, time, packet);
                if (packet instanceof PacketLogin) {
                    clientEntityId = ((PacketLogin) packet).entityId;
                } else if (packet instanceof PacketSpawnPosition) {
                    System.out.println(1);
                    PacketSpawnPosition p = (PacketSpawnPosition) packet;
                    if (!spawned) {
                        clientX = p.location.getX() * 32;
                        clientY = p.location.getY() * 32;
                        clientZ = p.location.getZ() * 32;
                    }
                } else if (packet instanceof de.johni0702.mc.protocolgen.play.client.PacketPosition) {
                    de.johni0702.mc.protocolgen.play.client.PacketPosition p =
                            (de.johni0702.mc.protocolgen.play.client.PacketPosition) packet;
                    int x = (int) (p.x * 32) + ((p.flags & 0x01) != 0x00 ? clientX : 0);
                    int y = (int) (p.y * 32) + ((p.flags & 0x02) != 0x00 ? clientY : 0);
                    int z = (int) (p.z * 32) + ((p.flags & 0x04) != 0x00 ? clientZ : 0);
                    byte yaw = (byte) ((p.yaw * 32) + ((p.flags & 0x08) != 0x00 ? clientYaw : 0));
                    byte pitch = (byte) ((p.pitch * 32) + ((p.flags & 0x10) != 0x00 ? clientPitch : 0));
                    writePacket(os, time, movement(clientEntityId, clientX, clientY, clientZ, clientYaw, clientPitch,
                            x, y, z, yaw, pitch, false));
                } else if (packet instanceof PacketPlayerInfo) {
                    PacketPlayerInfo p = (PacketPlayerInfo) packet;
                    if (p.action == 0) {
                        UUID uuid = p.data[0].uuid;
                        if (clientUuid.equals(uuid) && !spawned) {
                            System.out.println(2);
                            PacketNamedEntitySpawn spawn = new PacketNamedEntitySpawn();
                            spawn.entityId = clientEntityId;
                            spawn.playerUUID = clientUuid;
                            spawn.x = clientX;
                            spawn.y = clientY;
                            spawn.z = clientZ;
                            spawn.yaw = clientYaw;
                            spawn.pitch = clientPitch;
                            spawn.currentItem = hotbar[selectedHotbarSlot];
                            spawn.metadata = new EntityMetadata();
                            writePacket(os, time, spawn);
                            spawned = true;
                        }
                    }
                }
                if (packet instanceof de.johni0702.mc.protocolgen.play.client.PacketHeldItemSlot) {
                    selectedHotbarSlot = ((de.johni0702.mc.protocolgen.play.client.PacketHeldItemSlot) packet).slot;
                }
            } else {
                if (packet instanceof PacketKeepAlive
                        || packet instanceof PacketCustomPayload
                        || packet instanceof PacketSettings
                        || packet instanceof PacketFlying
                        || packet instanceof PacketBlockPlace
                        || packet instanceof PacketEntityAction
                        || packet instanceof PacketBlockDig) {
                    continue;
                }

                if (packet instanceof de.johni0702.mc.protocolgen.play.server.PacketHeldItemSlot) {
                    selectedHotbarSlot = ((de.johni0702.mc.protocolgen.play.server.PacketHeldItemSlot) packet).slotId;
                } else if (packet instanceof PacketPosition) {
                    PacketPosition p = (PacketPosition) packet;
                    writePacket(os, time, movement(clientEntityId, clientX, clientY, clientZ, clientYaw, clientPitch,
                            (int) (p.x * 32), (int) (p.y * 32), (int) (p.z * 32),
                            clientYaw, clientPitch, p.onGround));
                } else if (packet instanceof PacketLook) {
                    PacketLook p = (PacketLook) packet;
                    byte yaw = (byte) (p.yaw / 360 * 256);
                    writePacket(os, time, movement(clientEntityId, clientX, clientY, clientZ, clientYaw, clientPitch,
                            clientX, clientY, clientZ, yaw, (byte) (p.pitch / 360 * 256), p.onGround));
                    PacketEntityHeadRotation head = new PacketEntityHeadRotation();
                    head.entityId = clientEntityId;
                    head.headYaw = yaw;
                    writePacket(os, time, head);
                } else if (packet instanceof PacketPositionLook) {
                    PacketPositionLook p = (PacketPositionLook) packet;
                    byte yaw = (byte) (p.yaw / 360 * 256);
                    byte pitch = (byte) (p.pitch / 360 * 256);
                    writePacket(os, time, movement(clientEntityId, clientX, clientY, clientZ, clientYaw, clientPitch,
                            (int) (p.x * 32), (int) (p.y * 32), (int) (p.z * 32), yaw, pitch, p.onGround));
                    PacketEntityHeadRotation head = new PacketEntityHeadRotation();
                    head.entityId = clientEntityId;
                    head.headYaw = yaw;
                    writePacket(os, time, head);
                } else if (packet instanceof PacketArmAnimation) {
                    PacketAnimation anim = new PacketAnimation();
                    anim.entityId = clientEntityId;
                    anim.animation = ANIMATION_SWING_ARM;
                    writePacket(os, time, anim);
                } else if (packet instanceof PacketBlockDig) {

                } else {
                    System.out.println(packet);
                }
            }
        }
    }

    private static Packet movement(int clientEntityId,
                                   int clientX, int clientY, int clientZ, byte clientYaw, byte clientPitch,
                                   int x, int y, int z, byte yaw, byte pitch, boolean onGround) {
        if (Math.abs(clientX - x) > 256
                || Math.abs(clientY - y) > 256
                || Math.abs(clientZ - z) > 256) {
            PacketEntityTeleport packet = new PacketEntityTeleport();
            packet.entityId = clientEntityId;
            packet.x = x;
            packet.y = y;
            packet.z = z;
            packet.yaw = yaw;
            packet.pitch = pitch;
            packet.onGround = onGround;
            return packet;
        } else {
            if (clientX != x || clientY != y || clientZ != z) {
                if (clientYaw != yaw || clientPitch != pitch) {
                    PacketEntityMoveLook packet = new PacketEntityMoveLook();
                    packet.entityId = clientEntityId;
                    packet.dX = (byte) (x - clientX);
                    packet.dY = (byte) (y - clientY);
                    packet.dZ = (byte) (z - clientZ);
                    packet.yaw = yaw;
                    packet.pitch = pitch;
                    packet.onGround = onGround;
                    return packet;
                } else {
                    PacketRelEntityMove packet = new PacketRelEntityMove();
                    packet.entityId = clientEntityId;
                    packet.dX = (byte) (x - clientX);
                    packet.dY = (byte) (y - clientY);
                    packet.dZ = (byte) (z - clientZ);
                    packet.onGround = onGround;
                    return packet;
                }
            } else {
                PacketEntityLook packet = new PacketEntityLook();
                packet.entityId = clientEntityId;
                packet.yaw = yaw;
                packet.pitch = pitch;
                packet.onGround = onGround;
                return packet;
            }
        }
    }

    private static void writePacket(DataOutputStream os, long time, Packet packet) throws IOException {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer();
        NetUtils.writeVarInt(buf, REVERSE_PACKET_MAP.get(packet.getClass()));
        packet.write(buf);
        int length = buf.readableBytes();

        os.writeInt((int) time);
        os.writeInt(length);
        buf.readBytes(os, length);
        buf.release();
    }

    private static long readLong(InputStream is) throws IOException {
        return  ((long) is.read()) << 56 |
                ((long) is.read()) << 48 |
                ((long) is.read()) << 40 |
                ((long) is.read()) << 32 |
                ((long) is.read()) << 24 |
                ((long) is.read()) << 16 |
                ((long) is.read()) <<  8 |
                (long) is.read();
    }

    private static long readVar(InputStream is) throws IOException {
        long var = 0;
        int shift = 0;
        int b;
        do {
            if ((b = is.read()) == -1) {
                return -1;
            }
            var |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return var;
    }
}
