(function () {
  'use strict';

  var channel = function (payload) {
    try {
      if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.chronos) {
        window.webkit.messageHandlers.chronos.postMessage(payload);
      }
    } catch (error) {
      console.warn('Chronos iOS bridge:', error);
    }
  };

  var alarmKey = 'chronos-ios-alarms-v1';

  function readAlarms() {
    try {
      var value = localStorage.getItem(alarmKey);
      var list = value ? JSON.parse(value) : [];
      return Array.isArray(list) ? list : [];
    } catch (error) {
      return [];
    }
  }

  function writeAlarms(list) {
    try {
      localStorage.setItem(alarmKey, JSON.stringify(list));
    } catch (error) {
      console.warn('Não foi possível salvar os alarmes localmente.', error);
    }
  }

  window.ChronosCompass = {
    searchMapPlace: function (query) {
      channel({ action: 'searchMapPlace', query: String(query || '') });
    },
    startCompass: function () {
      channel({ action: 'startCompass' });
      return true;
    },
    stopCompass: function () {
      channel({ action: 'stopCompass' });
    },
    searchWeatherCity: function (query) {
      channel({ action: 'searchWeatherCity', query: String(query || '') });
    },
    requestWeatherLocation: function () {
      channel({ action: 'requestWeatherLocation' });
    },
    fetchWeather: function (latitude, longitude, cityName, admin1, country) {
      channel({
        action: 'fetchWeather',
        latitude: Number(latitude),
        longitude: Number(longitude),
        cityName: String(cityName || ''),
        admin1: String(admin1 || ''),
        country: String(country || '')
      });
    },
    connectWeatherWidget: function () {
      channel({ action: 'connectWeatherWidget' });
    },
    setWeatherWidgetBounds: function (left, top, width, height) {
      channel({ action: 'setWeatherWidgetBounds', left: left, top: top, width: width, height: height });
    },
    setWeatherTabVisible: function (visible) {
      channel({ action: 'setWeatherTabVisible', visible: !!visible });
    }
  };

  window.ChronosAlarm = {
    getAlarms: function () {
      return JSON.stringify(readAlarms());
    },
    saveAlarm: function (json) {
      try {
        var alarm = typeof json === 'string' ? JSON.parse(json) : json;
        if (!alarm || typeof alarm !== 'object') return;
        var list = readAlarms();
        var index = list.findIndex(function (item) { return String(item.id) === String(alarm.id); });
        if (index >= 0) list[index] = alarm;
        else list.push(alarm);
        writeAlarms(list);
        channel({ action: 'saveAlarm', alarm: alarm });
      } catch (error) {
        console.warn('Não foi possível salvar o alarme.', error);
      }
    },
    setAlarmEnabled: function (id, enabled) {
      var list = readAlarms();
      list.forEach(function (item) {
        if (String(item.id) === String(id)) item.enabled = !!enabled;
      });
      writeAlarms(list);
      channel({ action: 'setAlarmEnabled', id: String(id), enabled: !!enabled });
    },
    deleteAlarm: function (id) {
      writeAlarms(readAlarms().filter(function (item) { return String(item.id) !== String(id); }));
      channel({ action: 'deleteAlarm', id: String(id) });
    },
    pickAlarmTone: function () {
      window.onChronosAlarmTonePicked && window.onChronosAlarmTonePicked('chronos-ios-default', 'Som padrão do iPhone');
    },
    requestExactAlarmPermission: function () {
      channel({ action: 'requestAlarmPermission' });
    },
    openSystemClock: function () {
      channel({ action: 'openSystemClock' });
    }
  };
})();
