package sexy.kostya.proto4j.exception;

import sexy.kostya.proto4j.rpc.serialization.Proto4jSerializable;
import sexy.kostya.proto4j.transport.buffer.Buffer;

/**
 * Created by k.shandurenko on 02.10.2020
 */
public class RpcException extends Proto4jException implements Proto4jSerializable {

    private int    code;
    private String message;

    public RpcException() {
    }

    public RpcException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return this.code;
    }

    @Override
    public String getMessage() {
        return this.message;
    }

    @Override
    public void write(Buffer buffer) {
        buffer.writeVarInt(this.code);
        buffer.writeStringMaybe(getMessage());
    }

    @Override
    public void read(Buffer buffer) {
        this.code = buffer.readVarInt();
        this.message = buffer.readStringMaybe();
    }

    public static class Code {

        public final static int NO_SERVICE_AVAILABLE = 1000;
        public final static int SIGNATURE_MISMATCH   = 1001;
        public final static int INVOCATION_EXCEPTION = 1002;

    }

}