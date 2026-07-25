using System;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

namespace KoboPageTurnerUWP.Services
{
    public enum HidKeyCode : byte
    {
        // USB HID Usage Tables, Keyboard/Keypad page (0x07).
        RightArrow = 0x4F,
        LeftArrow = 0x50,
    }

    // Makes the phone advertise itself as a BLE HID keyboard (HID over GATT
    // Profile) so Kobo's native "Bluetooth accessories" page-turn support
    // recognizes it, the same way it recognizes any Bluetooth keyboard. No
    // ready-made UWP C# example exists for this (Arduino/ESP32 equivalents
    // rely on a library not available here) — implemented directly from the
    // Bluetooth SIG HOGP spec.
    public sealed class BleKeyboardService
    {
        // Standard USB HID "Boot Keyboard" report descriptor, Report ID 1 —
        // the same descriptor used by virtually every BLE keyboard reference
        // design. 8-byte input report: [modifier, reserved, key1..key6].
        private static readonly byte[] ReportMap =
        {
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x06,       // Usage (Keyboard)
            0xA1, 0x01,       // Collection (Application)
            0x85, 0x01,       //   Report Id (1)
            0x05, 0x07,       //   Usage Page (Key Codes)
            0x19, 0xE0,       //   Usage Minimum (224)
            0x29, 0xE7,       //   Usage Maximum (231)
            0x15, 0x00,       //   Logical Minimum (0)
            0x25, 0x01,       //   Logical Maximum (1)
            0x75, 0x01,       //   Report Size (1)
            0x95, 0x08,       //   Report Count (8)
            0x81, 0x02,       //   Input (Data, Variable, Absolute) — modifier byte
            0x95, 0x01,       //   Report Count (1)
            0x75, 0x08,       //   Report Size (8)
            0x81, 0x01,       //   Input (Constant) — reserved byte
            0x95, 0x05,       //   Report Count (5)
            0x75, 0x01,       //   Report Size (1)
            0x05, 0x08,       //   Usage Page (LEDs)
            0x19, 0x01,       //   Usage Minimum (1)
            0x29, 0x05,       //   Usage Maximum (5)
            0x91, 0x02,       //   Output (Data, Variable, Absolute) — LED report
            0x95, 0x01,       //   Report Count (1)
            0x75, 0x03,       //   Report Size (3)
            0x91, 0x01,       //   Output (Constant) — LED padding
            0x95, 0x06,       //   Report Count (6)
            0x75, 0x08,       //   Report Size (8)
            0x15, 0x00,       //   Logical Minimum (0)
            0x25, 0x65,       //   Logical Maximum (101)
            0x05, 0x07,       //   Usage Page (Key Codes)
            0x19, 0x00,       //   Usage Minimum (0)
            0x29, 0x65,       //   Usage Maximum (101)
            0x81, 0x00,       //   Input (Data, Array) — key array (6 bytes)
            0xC0,             // End Collection
        };

        // Bluetooth SIG 16-bit assigned numbers, expanded against the
        // standard Bluetooth base UUID — avoids depending on exact member
        // names in the GattServiceUuids/GattCharacteristicUuids helper
        // classes, which don't reliably expose the HID-specific ones.
        private static Guid Uuid16(ushort assignedNumber) =>
            new Guid(assignedNumber, 0x0000, 0x1000, 0x80, 0x00, 0x00, 0x80, 0x5F, 0x9B, 0x34, 0xFB);

        private static readonly Guid ServiceHid = Uuid16(0x1812);
        private static readonly Guid ServiceDeviceInformation = Uuid16(0x180A);
        private static readonly Guid ServiceBattery = Uuid16(0x180F);

        private static readonly Guid CharHidInformation = Uuid16(0x2A4A);
        private static readonly Guid CharReportMap = Uuid16(0x2A4B);
        private static readonly Guid CharHidControlPoint = Uuid16(0x2A4C);
        private static readonly Guid CharReport = Uuid16(0x2A4D);
        private static readonly Guid CharProtocolMode = Uuid16(0x2A4E);
        private static readonly Guid CharManufacturerName = Uuid16(0x2A29);
        private static readonly Guid CharPnpId = Uuid16(0x2A50);
        private static readonly Guid CharBatteryLevel = Uuid16(0x2A19);
        private static readonly Guid DescriptorReportReference = Uuid16(0x2908);

        private GattServiceProvider _hidProvider;
        private GattServiceProvider _deviceInfoProvider;
        private GattServiceProvider _batteryProvider;
        private GattLocalCharacteristic _reportCharacteristic;

        public bool IsPeripheralRoleSupported { get; private set; }

        public bool HasSubscribedClient =>
            _reportCharacteristic != null && _reportCharacteristic.SubscribedClients.Count > 0;

        public event EventHandler SubscribedClientsChanged;

        public async Task<bool> CheckPeripheralSupportAsync()
        {
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            IsPeripheralRoleSupported = adapter != null && adapter.IsPeripheralRoleSupported;
            return IsPeripheralRoleSupported;
        }

        public async Task<bool> StartAsync()
        {
            if (!IsPeripheralRoleSupported)
            {
                return false;
            }

            await CreateDeviceInformationServiceAsync();
            await CreateBatteryServiceAsync();
            await CreateHidServiceAsync();

            var advertisingParameters = new GattServiceProviderAdvertisingParameters
            {
                IsConnectable = true,
                IsDiscoverable = true,
            };
            _hidProvider.StartAdvertising(advertisingParameters);
            return true;
        }

        public void Stop()
        {
            _hidProvider?.StopAdvertising();
        }

        public async Task SendKeyAsync(HidKeyCode key)
        {
            if (_reportCharacteristic == null)
            {
                return;
            }

            var pressed = new byte[8];
            pressed[2] = (byte)key;
            await NotifyReportAsync(pressed);

            await Task.Delay(30);

            await NotifyReportAsync(new byte[8]);
        }

        private async Task NotifyReportAsync(byte[] report)
        {
            var writer = new DataWriter();
            writer.WriteBytes(report);
            await _reportCharacteristic.NotifyValueAsync(writer.DetachBuffer());
        }

        private async Task CreateHidServiceAsync()
        {
            var serviceResult = await GattServiceProvider.CreateAsync(ServiceHid);
            if (serviceResult.Error != BluetoothError.Success)
            {
                throw new InvalidOperationException("Could not create HID GATT service: " + serviceResult.Error);
            }
            _hidProvider = serviceResult.ServiceProvider;
            var service = _hidProvider.Service;

            var hidInfoWriter = new DataWriter { ByteOrder = ByteOrder.LittleEndian };
            hidInfoWriter.WriteUInt16(0x0111); // bcdHID 1.11
            hidInfoWriter.WriteByte(0x00);     // country code: not localized
            hidInfoWriter.WriteByte(0x02);     // flags: RemoteWake
            await service.CreateCharacteristicAsync(CharHidInformation, new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Read,
                ReadProtectionLevel = GattProtectionLevel.Plain,
                StaticValue = hidInfoWriter.DetachBuffer(),
            });

            var reportMapWriter = new DataWriter();
            reportMapWriter.WriteBytes(ReportMap);
            await service.CreateCharacteristicAsync(CharReportMap, new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Read,
                ReadProtectionLevel = GattProtectionLevel.Plain,
                StaticValue = reportMapWriter.DetachBuffer(),
            });

            var protocolModeWriter = new DataWriter();
            protocolModeWriter.WriteByte(0x01); // Report Protocol Mode
            await service.CreateCharacteristicAsync(CharProtocolMode, new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Read | GattCharacteristicProperties.WriteWithoutResponse,
                ReadProtectionLevel = GattProtectionLevel.Plain,
                WriteProtectionLevel = GattProtectionLevel.Plain,
                StaticValue = protocolModeWriter.DetachBuffer(),
            });

            await service.CreateCharacteristicAsync(CharHidControlPoint, new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.WriteWithoutResponse,
                WriteProtectionLevel = GattProtectionLevel.Plain,
            });

            var reportResult = await service.CreateCharacteristicAsync(CharReport, new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Read | GattCharacteristicProperties.Notify,
                ReadProtectionLevel = GattProtectionLevel.Plain,
                StaticValue = new DataWriter().DetachBuffer(), // all-zero (no key pressed) until first send
            });
            _reportCharacteristic = reportResult.Characteristic;
            _reportCharacteristic.SubscribedClientsChanged += (sender, args) =>
                SubscribedClientsChanged?.Invoke(this, EventArgs.Empty);

            var reportRefWriter = new DataWriter();
            reportRefWriter.WriteByte(0x01); // Report ID 1
            reportRefWriter.WriteByte(0x01); // Report Type: Input
            await _reportCharacteristic.CreateDescriptorAsync(DescriptorReportReference, new GattLocalDescriptorParameters
            {
                ReadProtectionLevel = GattProtectionLevel.Plain,
                StaticValue = reportRefWriter.DetachBuffer(),
            });
        }

        private async Task CreateDeviceInformationServiceAsync()
        {
            var serviceResult = await GattServiceProvider.CreateAsync(ServiceDeviceInformation);
            if (serviceResult.Error != BluetoothError.Success)
            {
                throw new InvalidOperationException("Could not create Device Information GATT service: " + serviceResult.Error);
            }
            _deviceInfoProvider = serviceResult.ServiceProvider;
            var service = _deviceInfoProvider.Service;

            var nameWriter = new DataWriter();
            nameWriter.WriteString("Kobo Page Turner");
            await service.CreateCharacteristicAsync(CharManufacturerName, new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Read,
                ReadProtectionLevel = GattProtectionLevel.Plain,
                StaticValue = nameWriter.DetachBuffer(),
            });

            var pnpWriter = new DataWriter { ByteOrder = ByteOrder.LittleEndian };
            pnpWriter.WriteByte(0x02);     // Vendor ID Source: USB-IF assigned
            pnpWriter.WriteUInt16(0xFFFF); // Vendor ID (placeholder — not Store-published)
            pnpWriter.WriteUInt16(0x0001); // Product ID
            pnpWriter.WriteUInt16(0x0100); // Product version 1.0
            await service.CreateCharacteristicAsync(CharPnpId, new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Read,
                ReadProtectionLevel = GattProtectionLevel.Plain,
                StaticValue = pnpWriter.DetachBuffer(),
            });
        }

        private async Task CreateBatteryServiceAsync()
        {
            var serviceResult = await GattServiceProvider.CreateAsync(ServiceBattery);
            if (serviceResult.Error != BluetoothError.Success)
            {
                throw new InvalidOperationException("Could not create Battery GATT service: " + serviceResult.Error);
            }
            _batteryProvider = serviceResult.ServiceProvider;
            var service = _batteryProvider.Service;

            var levelWriter = new DataWriter();
            levelWriter.WriteByte(100);
            await service.CreateCharacteristicAsync(CharBatteryLevel, new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Read,
                ReadProtectionLevel = GattProtectionLevel.Plain,
                StaticValue = levelWriter.DetachBuffer(),
            });
        }
    }
}
